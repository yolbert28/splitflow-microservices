package dev.yolbert.auth_service.service;

import dev.yolbert.auth_service.domain.entity.*;
import dev.yolbert.auth_service.dto.PasswordResetRequestCommand;
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
public class ResetPasswordRequestUseCase {

    private final UserRepository userRepository;
    private final OtpRepository otpRepository;
    private final OutboxRepository outboxRepository;

    public ResetPasswordRequestUseCase(UserRepository userRepository,
                                      OtpRepository otpRepository,
                                      OutboxRepository outboxRepository) {
        this.userRepository   = userRepository;
        this.otpRepository    = otpRepository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public void execute(PasswordResetRequestCommand command) {
        Optional<User> userOpt = userRepository.findByEmail(command.getEmail());
        if (userOpt.isEmpty()) {
            // Respond 200 OK without revealing email existence
            return;
        }

        User user = userOpt.get();

        // Invalidate previous PENDING PASSWORD_RESET OTP if any
        otpRepository.findTopByUserIdAndPurposeOrderByCreatedAtDesc(user.getId(), OtpPurpose.PASSWORD_RESET)
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
                .purpose(OtpPurpose.PASSWORD_RESET)
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
                .eventType("USER_PASSWORD_RESET_OTP")
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
