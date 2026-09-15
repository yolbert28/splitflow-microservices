package dev.yolbert.auth_service.service;

import dev.yolbert.auth_service.domain.entity.Otp;
import dev.yolbert.auth_service.domain.entity.OtpPurpose;
import dev.yolbert.auth_service.domain.entity.OtpStatus;
import dev.yolbert.auth_service.domain.entity.User;
import dev.yolbert.auth_service.dto.VerifyEmailCommand;
import dev.yolbert.auth_service.dto.VerifyEmailResponseData;
import dev.yolbert.auth_service.repository.OtpRepository;
import dev.yolbert.auth_service.repository.UserRepository;
import dev.yolbert.auth_service.utils.exceptions.InvalidOtpException;
import dev.yolbert.auth_service.utils.exceptions.TooManyOtpAttemptsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

@Service
public class VerifyEmailUseCase {

    private static final String INVALID_OR_EXPIRED_MSG = "El código ingresado es inválido o ha expirado.";
    private static final String TOO_MANY_ATTEMPTS_MSG = "Demasiados intentos. Intenta de nuevo en unos minutos.";

    private final UserRepository userRepository;
    private final OtpRepository otpRepository;

    public VerifyEmailUseCase(UserRepository userRepository, OtpRepository otpRepository) {
        this.userRepository = userRepository;
        this.otpRepository = otpRepository;
    }

    @Transactional(noRollbackFor = {InvalidOtpException.class, TooManyOtpAttemptsException.class})
    public VerifyEmailResponseData execute(VerifyEmailCommand command) {
        User user = userRepository.findByEmail(command.getEmail())
                .orElseThrow(() -> new InvalidOtpException(INVALID_OR_EXPIRED_MSG));

        Otp otp = otpRepository.findTopByUserIdAndPurposeOrderByCreatedAtDesc(user.getId(), OtpPurpose.EMAIL_VERIFICATION)
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

            LocalDateTime now = LocalDateTime.now();
            user.setVerifiedAt(now);
            user.setUpdatedAt(now);
            userRepository.saveAndFlush(user);

            return VerifyEmailResponseData.builder()
                    .id(user.getId())
                    .email(user.getEmail())
                    .verified(true)
                    .verifiedAt(user.getVerifiedAt())
                    .build();
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
