package dev.yolbert.auth_service.service;

import dev.yolbert.auth_service.domain.entity.Session;
import dev.yolbert.auth_service.dto.LogoutCommand;
import dev.yolbert.auth_service.repository.SessionRepository;
import dev.yolbert.auth_service.utils.TokenHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
public class LogoutUseCase {

    private final SessionRepository sessionRepository;

    public LogoutUseCase(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /**
     * Revokes the session that matches the received refresh token. Idempotent:
     * a missing or already-revoked session is a successful no-op.
     *
     * <p>The token is hashed with SHA-256 before querying, which allows the
     * {@code idx_session_refresh_token_hash} index to be used instead of a
     * full-table scan.
     */
    @Transactional
    public void execute(LogoutCommand command) {
        sessionRepository.findByRefreshTokenHash(TokenHasher.hash(command.getRefreshToken()))
                .filter(session -> !session.isRevoked())
                .ifPresent(this::revoke);
    }

    private void revoke(Session session) {
        OffsetDateTime now = OffsetDateTime.now();
        session.setRevoked(true);
        session.setRevokedAt(now);
        session.setUpdatedAt(now);
        sessionRepository.save(session);
    }
}