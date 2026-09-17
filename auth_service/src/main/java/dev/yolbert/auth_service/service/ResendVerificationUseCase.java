package dev.yolbert.auth_service.service;

import dev.yolbert.auth_service.domain.entity.*;
import dev.yolbert.auth_service.dto.ResendVerificationCommand;
import dev.yolbert.auth_service.repository.OtpRepository;
import dev.yolbert.auth_service.repository.OutboxRepository;
import dev.yolbert.auth_service.repository.UserRepository;
import dev.yolbert.auth_service.utils.OtpCodeGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class ResendVerificationUseCase {

    private final UserRepository userRepository;
    private final OtpRepository otpRepository;
    private final OutboxRepository outboxRepository;

    public ResendVerificationUseCase(UserRepository userRepository,
                                     OtpRepository otpRepository,
                                     OutboxRepository outboxRepository) {
        this.userRepository   = userRepository;
        this.otpRepository    = otpRepository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public void execute(ResendVerificationCommand command) {
        Optional<User> userOpt = userRepository.findByEmail(command.getEmail());
        if (userOpt.isEmpty() || userOpt.get().getVerifiedAt() != null) {
            // Respond 200 OK without revealing reason
            return;
        }

        User user = userOpt.get();

        // Invalidate old PENDING EMAIL_VERIFICATION OTP if any
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
