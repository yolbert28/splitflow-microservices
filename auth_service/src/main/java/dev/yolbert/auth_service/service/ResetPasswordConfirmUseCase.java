package dev.yolbert.auth_service.service;

import dev.yolbert.auth_service.domain.entity.*;
import dev.yolbert.auth_service.dto.PasswordResetConfirmCommand;
import dev.yolbert.auth_service.repository.OtpRepository;
import dev.yolbert.auth_service.repository.SessionRepository;
import dev.yolbert.auth_service.repository.UserRepository;
import dev.yolbert.auth_service.utils.exceptions.InvalidOtpException;
import dev.yolbert.auth_service.utils.exceptions.TooManyOtpAttemptsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class ResetPasswordConfirmUseCase {

    private static final String INVALID_OR_EXPIRED_MSG = "El código ingresado es inválido o ha expirado.";
    private static final String TOO_MANY_ATTEMPTS_MSG = "Demasiados intentos. Intenta de nuevo en unos minutos.";

    private final UserRepository userRepository;
    private final OtpRepository otpRepository;
    private final SessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;

    public ResetPasswordConfirmUseCase(UserRepository userRepository,
                                       OtpRepository otpRepository,
                                       SessionRepository sessionRepository,
                                       PasswordEncoder passwordEncoder) {
        this.userRepository    = userRepository;
        this.otpRepository     = otpRepository;
        this.sessionRepository = sessionRepository;
        this.passwordEncoder   = passwordEncoder;
    }

    @Transactional(noRollbackFor = {InvalidOtpException.class, TooManyOtpAttemptsException.class})
    public void execute(PasswordResetConfirmCommand command) {
        User user = userRepository.findByEmail(command.getEmail())
                .orElseThrow(() -> new InvalidOtpException(INVALID_OR_EXPIRED_MSG));

        Otp otp = otpRepository.findTopByUserIdAndPurposeOrderByCreatedAtDesc(user.getId(), OtpPurpose.PASSWORD_RESET)
                .orElseThrow(() -> new InvalidOtpException(INVALID_OR_EXPIRED_MSG));

        if (otp.getStatus() == OtpStatus.EXPIRED || otp.getAttempts() >= 5) {
            throw new TooManyOtpAttemptsException(TOO_MANY_ATTEMPTS_MSG);
        }

        if (otp.getStatus() != OtpStatus.PENDING ||
                (otp.getExpiresAt() != null && otp.getExpiresAt().isBefore(OffsetDateTime.now()))) {
            throw new InvalidOtpException(INVALID_OR_EXPIRED_MSG);
        }

        if (otp.getCode().equals(command.getOtpCode())) {
            otp.setStatus(OtpStatus.VERIFIED);
            otpRepository.saveAndFlush(otp);

            LocalDateTime nowLocal = LocalDateTime.now();
            user.setPasswordHash(passwordEncoder.encode(command.getNewPassword()));
            user.setUpdatedAt(nowLocal);
            userRepository.saveAndFlush(user);

            // Revoke all active sessions
            List<Session> activeSessions = sessionRepository.findAllByUserIdAndRevokedFalse(user.getId());
            OffsetDateTime nowOffset = OffsetDateTime.now();
            for (Session session : activeSessions) {
                session.setRevoked(true);
                session.setRevokedAt(nowOffset);
                session.setUpdatedAt(nowOffset);
            }
            sessionRepository.saveAll(activeSessions);
        } else {
            int attempts = otp.getAttempts() + 1;
            otp.setAttempts(attempts);

            if (attempts >= 5) {
                otp.setStatus(OtpStatus.EXPIRED);
                otpRepository.saveAndFlush(otp);
                throw new TooManyOtpAttemptsException(TOO_MANY_ATTEMPTS_MSG);
            } else {
                otpRepository.saveAndFlush(otp);
                throw new InvalidOtpException(INVALID_OR_EXPIRED_MSG);
            }
        }
    }
}
