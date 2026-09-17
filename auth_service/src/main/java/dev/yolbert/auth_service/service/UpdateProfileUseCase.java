package dev.yolbert.auth_service.service;

import dev.yolbert.auth_service.domain.entity.*;
import dev.yolbert.auth_service.dto.UpdateProfileCommand;
import dev.yolbert.auth_service.dto.UserResponseData;
import dev.yolbert.auth_service.mapper.UserMapper;
import dev.yolbert.auth_service.repository.OtpRepository;
import dev.yolbert.auth_service.repository.OutboxRepository;
import dev.yolbert.auth_service.repository.UserRepository;
import dev.yolbert.auth_service.utils.OtpCodeGenerator;
import dev.yolbert.auth_service.utils.exceptions.EmailConflictException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class UpdateProfileUseCase {

    private final UserRepository userRepository;
    private final OtpRepository otpRepository;
    private final OutboxRepository outboxRepository;

    public UpdateProfileUseCase(UserRepository userRepository,
                                 OtpRepository otpRepository,
                                 OutboxRepository outboxRepository) {
        this.userRepository   = userRepository;
        this.otpRepository    = otpRepository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public UserResponseData execute(UUID userId, UpdateProfileCommand command) {
        if (command.getFullName() == null && command.getEmail() == null && !command.isPhotoUrlSet()) {
            throw new IllegalArgumentException("Al menos un campo debe ser proporcionado para la actualización.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado."));

        boolean updated = false;

        if (command.getFullName() != null) {
            user.setFullName(command.getFullName());
            updated = true;
        }

        if (command.isPhotoUrlSet()) {
            user.setPhotoUrl(command.getPhotoUrl());
            updated = true;
        }

        if (command.getEmail() != null && !command.getEmail().equalsIgnoreCase(user.getEmail())) {
            if (userRepository.existsByEmail(command.getEmail())) {
                throw new EmailConflictException(command.getEmail());
            }

            user.setEmail(command.getEmail());
            user.setVerifiedAt(null);
            updated = true;

            // Invalidate old EMAIL_VERIFICATION OTP if any
            otpRepository.findTopByUserIdAndPurposeOrderByCreatedAtDesc(user.getId(), OtpPurpose.EMAIL_VERIFICATION)
                    .ifPresent(oldOtp -> {
                        if (oldOtp.getStatus() == OtpStatus.PENDING) {
                            oldOtp.setStatus(OtpStatus.EXPIRED);
                            otpRepository.save(oldOtp);
                        }
                    });

            String otpCode = OtpCodeGenerator.generate();
            OffsetDateTime now = OffsetDateTime.now();

            Otp otp = Otp.builder()
                    .id(UUID.randomUUID())
                    .userId(user.getId())
                    .code(otpCode)
                    .purpose(OtpPurpose.EMAIL_VERIFICATION)
                    .attempts(0)
                    .status(OtpStatus.PENDING)
                    .createdAt(now)
                    .expiresAt(now.plusMinutes(15))
                    .build();

            otpRepository.save(otp);
            publishOutboxEvent(user, otpCode);
        }

        if (updated) {
            user.setUpdatedAt(LocalDateTime.now());
            userRepository.save(user);
        }

        return UserMapper.toResponseData(user);
    }

    private void publishOutboxEvent(User user, String otpCode) {
        OffsetDateTime now = OffsetDateTime.now();

        Outbox event = Outbox.builder()
                .id(UUID.randomUUID())
                .aggregateId(user.getId())
                .aggregateType("USER")
                .eventType("USER_EMAIL_VERIFICATION")
                .payload(buildPayload(user, otpCode))
                .status(OutboxStatus.PENDING)
                .createdAt(now)
                .updatedAt(now)
                .build();

        outboxRepository.save(event);
    }

    private String buildPayload(User user, String otpCode) {
        return """
                {"id":"%s","email":"%s","otp_code":"%s"}""".formatted(
                user.getId(),
                escapeJson(user.getEmail()),
                escapeJson(otpCode)
        );
    }

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
