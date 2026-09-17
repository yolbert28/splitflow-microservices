package dev.yolbert.auth_service.service;

import dev.yolbert.auth_service.domain.entity.Otp;
import dev.yolbert.auth_service.domain.entity.OtpPurpose;
import dev.yolbert.auth_service.domain.entity.OtpStatus;
import dev.yolbert.auth_service.domain.entity.Outbox;
import dev.yolbert.auth_service.domain.entity.OutboxStatus;
import dev.yolbert.auth_service.domain.entity.User;
import dev.yolbert.auth_service.dto.LoginCommand;
import dev.yolbert.auth_service.repository.OtpRepository;
import dev.yolbert.auth_service.repository.OutboxRepository;
import dev.yolbert.auth_service.repository.UserRepository;
import dev.yolbert.auth_service.utils.OtpCodeGenerator;
import dev.yolbert.auth_service.utils.exceptions.InvalidCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class LoginUseCase {

    private static final String INVALID_CREDENTIALS_MSG = "Credenciales inválidas.";
    private static final long OTP_TTL_MINUTES = 15;

    private final UserRepository userRepository;
    private final OtpRepository otpRepository;
    private final OutboxRepository outboxRepository;
    private final PasswordEncoder passwordEncoder;

    public LoginUseCase(UserRepository userRepository,
                        OtpRepository otpRepository,
                        OutboxRepository outboxRepository,
                        PasswordEncoder passwordEncoder) {
        this.userRepository   = userRepository;
        this.otpRepository    = otpRepository;
        this.outboxRepository = outboxRepository;
        this.passwordEncoder  = passwordEncoder;
    }

    /**
     * Validates credentials and issues a LOGIN_2FA OTP when they are correct.
     *
     * <p>All failure conditions (unknown email, wrong password, unverified
     * account) throw the same {@link InvalidCredentialsException} so the
     * response does not reveal whether the email exists.
     */
    @Transactional
    public void execute(LoginCommand command) {
        User user = userRepository.findByEmailAndDeletedAtIsNull(command.getEmail())
                .orElseThrow(() -> new InvalidCredentialsException(INVALID_CREDENTIALS_MSG));

        if (!passwordEncoder.matches(command.getPassword(), user.getPasswordHash())
                || user.getVerifiedAt() == null) {
            throw new InvalidCredentialsException(INVALID_CREDENTIALS_MSG);
        }

        invalidatePendingOtp(user.getId());

        String otpCode = OtpCodeGenerator.generate();
        OffsetDateTime now = OffsetDateTime.now();

        Otp otp = Otp.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .code(otpCode)
                .purpose(OtpPurpose.LOGIN_2FA)
                .attempts(0)
                .status(OtpStatus.PENDING)
                .createdAt(now)
                .expiresAt(now.plusMinutes(OTP_TTL_MINUTES))
                .build();
        otpRepository.save(otp);

        publishToOutbox(user, otpCode);
    }

    private void invalidatePendingOtp(UUID userId) {
        otpRepository.findTopByUserIdAndPurposeOrderByCreatedAtDesc(userId, OtpPurpose.LOGIN_2FA)
                .filter(otp -> otp.getStatus() == OtpStatus.PENDING)
                .ifPresent(otp -> {
                    otp.setStatus(OtpStatus.EXPIRED);
                    otpRepository.save(otp);
                });
    }

    private void publishToOutbox(User user, String otpCode) {
        OffsetDateTime now = OffsetDateTime.now();

        Outbox event = Outbox.builder()
                .id(UUID.randomUUID())
                .aggregateId(user.getId())
                .aggregateType("USER")
                .eventType("USER_LOGIN_OTP")
                .payload(buildPayload(user, otpCode))
                .status(OutboxStatus.PENDING)
                .createdAt(now)
                .updatedAt(now)
                .build();

        outboxRepository.save(event);
    }

    /**
     * Builds a minimal JSON payload without external serialization libraries.
     * Structure is fixed: {id, email, otp_code} — safe to construct via template.
     */
    private String buildPayload(User user, String otpCode) {
        return """
                {"id":"%s","email":"%s","otp_code":"%s"}""".formatted(
                user.getId(),
                escapeJson(user.getEmail()),
                escapeJson(otpCode)
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