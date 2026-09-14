package dev.yolbert.auth_service.service;

import dev.yolbert.auth_service.domain.entity.Otp;
import dev.yolbert.auth_service.domain.entity.OtpPurpose;
import dev.yolbert.auth_service.domain.entity.OtpStatus;
import dev.yolbert.auth_service.domain.entity.Outbox;
import dev.yolbert.auth_service.domain.entity.OutboxStatus;
import dev.yolbert.auth_service.domain.entity.User;
import dev.yolbert.auth_service.dto.RegisterUserCommand;
import dev.yolbert.auth_service.dto.UserResponseData;
import dev.yolbert.auth_service.mapper.UserMapper;
import dev.yolbert.auth_service.repository.OtpRepository;
import dev.yolbert.auth_service.repository.OutboxRepository;
import dev.yolbert.auth_service.repository.UserRepository;
import dev.yolbert.auth_service.utils.FriendCodeGenerator;
import dev.yolbert.auth_service.utils.OtpCodeGenerator;
import dev.yolbert.auth_service.utils.exceptions.EmailAlreadyExistsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class RegisterUseCase {

    private final UserRepository userRepository;
    private final OutboxRepository outboxRepository;
    private final OtpRepository otpRepository;
    private final PasswordEncoder passwordEncoder;

    public RegisterUseCase(UserRepository userRepository,
                           OutboxRepository outboxRepository,
                           OtpRepository otpRepository,
                           PasswordEncoder passwordEncoder) {
        this.userRepository   = userRepository;
        this.outboxRepository = outboxRepository;
        this.otpRepository    = otpRepository;
        this.passwordEncoder  = passwordEncoder;
    }

    /**
     * Registers a new user.
     *
     * <p>Steps (all within a single transaction):
     * <ol>
     *   <li>Verify the email is not already taken.</li>
     *   <li>Hash the password with BCrypt.</li>
     *   <li>Generate a URL-safe friend code.</li>
     *   <li>Persist the {@link User}.</li>
     *   <li>Generate and persist email verification OTP.</li>
     *   <li>Write a USER_REGISTERED event to the outbox table.</li>
     * </ol>
     *
     * @param command validated request data
     * @return response data for the 201 Created body
     */
    @Transactional
    public UserResponseData execute(RegisterUserCommand command) {
        if (userRepository.existsByEmail(command.getEmail())) {
            throw new EmailAlreadyExistsException(command.getEmail());
        }

        LocalDateTime now = LocalDateTime.now();
        UUID userId = UUID.randomUUID();

        User user = User.builder()
                .id(userId)
                .fullName(command.getFullName())
                .email(command.getEmail())
                .passwordHash(passwordEncoder.encode(command.getPassword()))
                .friendCode(FriendCodeGenerator.generate())
                .createdAt(now)
                .updatedAt(now)
                .build();

        userRepository.save(user);

        String otpCode = OtpCodeGenerator.generate();
        OffsetDateTime nowOffset = OffsetDateTime.now();
        Otp otp = Otp.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .code(otpCode)
                .purpose(OtpPurpose.EMAIL_VERIFICATION)
                .attempts(0)
                .status(OtpStatus.PENDING)
                .createdAt(nowOffset)
                .expiresAt(nowOffset.plusMinutes(15))
                .build();

        otpRepository.save(otp);

        publishToOutbox(user, otpCode);

        return UserMapper.toResponseData(user);
    }

    private void publishToOutbox(User user, String otpCode) {
        OffsetDateTime now = OffsetDateTime.now();

        Outbox event = Outbox.builder()
                .id(UUID.randomUUID())
                .aggregateId(user.getId())
                .aggregateType("USER")
                .eventType("USER_REGISTERED")
                .payload(buildPayload(user, otpCode))
                .status(OutboxStatus.PENDING)
                .createdAt(now)
                .updatedAt(now)
                .build();

        outboxRepository.save(event);
    }

    /**
     * Builds a minimal JSON payload without external serialization libraries.
     * Structure is fixed: {id, full_name, email, otp_code} — safe to construct via template.
     */
    private String buildPayload(User user, String otpCode) {
        return """
                {"id":"%s","full_name":"%s","email":"%s","otp_code":"%s"}""".formatted(
                user.getId(),
                escapeJson(user.getFullName()),
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
