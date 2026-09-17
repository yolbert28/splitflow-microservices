package dev.yolbert.auth_service.service;

import dev.yolbert.auth_service.domain.entity.Outbox;
import dev.yolbert.auth_service.domain.entity.OutboxStatus;
import dev.yolbert.auth_service.domain.entity.Session;
import dev.yolbert.auth_service.domain.entity.User;
import dev.yolbert.auth_service.repository.OutboxRepository;
import dev.yolbert.auth_service.repository.SessionRepository;
import dev.yolbert.auth_service.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class DeleteAccountUseCase {

    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final OutboxRepository outboxRepository;

    public DeleteAccountUseCase(UserRepository userRepository,
                                SessionRepository sessionRepository,
                                OutboxRepository outboxRepository) {
        this.userRepository    = userRepository;
        this.sessionRepository = sessionRepository;
        this.outboxRepository  = outboxRepository;
    }

    /**
     * Soft-deletes a user account: revokes all active sessions, sets
     * {@code deleted_at} and publishes a {@code USER_DELETED} event to the
     * outbox, all within a single transaction.
     *
     * <p>Idempotent: if the account is already deleted, the method returns
     * without modifying any data or publishing a duplicate event.
     */
    @Transactional
    public void execute(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado."));

        if (user.getDeletedAt() != null) {
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();
        List<Session> activeSessions = sessionRepository.findAllByUserIdAndRevokedFalse(userId);
        for (Session session : activeSessions) {
            session.setRevoked(true);
            session.setRevokedAt(now);
            session.setUpdatedAt(now);
        }
        sessionRepository.saveAll(activeSessions);

        LocalDateTime nowLocal = LocalDateTime.now();
        user.setDeletedAt(nowLocal);
        user.setUpdatedAt(nowLocal);
        userRepository.save(user);

        publishToOutbox(user);
    }

    private void publishToOutbox(User user) {
        OffsetDateTime now = OffsetDateTime.now();

        Outbox event = Outbox.builder()
                .id(UUID.randomUUID())
                .aggregateId(user.getId())
                .aggregateType("USER")
                .eventType("USER_DELETED")
                .payload(buildPayload(user))
                .status(OutboxStatus.PENDING)
                .createdAt(now)
                .updatedAt(now)
                .build();

        outboxRepository.save(event);
    }

    /** Builds a minimal JSON payload without external serialization libraries. */
    private String buildPayload(User user) {
        return """
                {"id":"%s","email":"%s"}""".formatted(
                user.getId(),
                escapeJson(user.getEmail())
        );
    }

    /** Escapes the minimal set of characters required for valid JSON strings. */
    private String escapeJson(String value) {
        if (value == null) return "";
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}