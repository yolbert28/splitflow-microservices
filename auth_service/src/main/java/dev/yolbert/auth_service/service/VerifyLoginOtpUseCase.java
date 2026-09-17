package dev.yolbert.auth_service.service;

import dev.yolbert.auth_service.config.JwtTokenProvider;
import dev.yolbert.auth_service.domain.entity.Otp;
import dev.yolbert.auth_service.domain.entity.OtpPurpose;
import dev.yolbert.auth_service.domain.entity.OtpStatus;
import dev.yolbert.auth_service.domain.entity.Session;
import dev.yolbert.auth_service.domain.entity.User;
import dev.yolbert.auth_service.dto.AuthTokenResponseData;
import dev.yolbert.auth_service.dto.VerifyLoginOtpCommand;
import dev.yolbert.auth_service.repository.OtpRepository;
import dev.yolbert.auth_service.repository.SessionRepository;
import dev.yolbert.auth_service.repository.UserRepository;
import dev.yolbert.auth_service.utils.exceptions.InvalidOtpException;
import dev.yolbert.auth_service.utils.exceptions.TooManyOtpAttemptsException;
import jakarta.servlet.http.HttpServletRequest;
import dev.yolbert.auth_service.utils.TokenHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class VerifyLoginOtpUseCase {

    private static final String INVALID_OR_EXPIRED_MSG = "El código ingresado es inválido o ha expirado.";
    private static final String TOO_MANY_ATTEMPTS_MSG = "Demasiados intentos. Intenta de nuevo en unos minutos.";
    private static final long SESSION_TTL_DAYS = 7;

    private final UserRepository userRepository;
    private final OtpRepository otpRepository;
    private final SessionRepository sessionRepository;
    private final JwtTokenProvider jwtTokenProvider;

    public VerifyLoginOtpUseCase(UserRepository userRepository,
                                 OtpRepository otpRepository,
                                 SessionRepository sessionRepository,
                                 JwtTokenProvider jwtTokenProvider) {
        this.userRepository    = userRepository;
        this.otpRepository     = otpRepository;
        this.sessionRepository = sessionRepository;
        this.jwtTokenProvider  = jwtTokenProvider;
    }

    @Transactional(noRollbackFor = {InvalidOtpException.class, TooManyOtpAttemptsException.class})
    public AuthTokenResponseData execute(VerifyLoginOtpCommand command, HttpServletRequest request) {
        User user = userRepository.findByEmail(command.getEmail())
                .orElseThrow(() -> new InvalidOtpException(INVALID_OR_EXPIRED_MSG));

        Otp otp = otpRepository.findTopByUserIdAndPurposeOrderByCreatedAtDesc(user.getId(), OtpPurpose.LOGIN_2FA)
                .orElseThrow(() -> new InvalidOtpException(INVALID_OR_EXPIRED_MSG));

        if (otp.getStatus() == OtpStatus.EXPIRED || otp.getAttempts() >= 5) {
            throw new TooManyOtpAttemptsException(TOO_MANY_ATTEMPTS_MSG);
        }

        if (otp.getStatus() != OtpStatus.PENDING ||
                (otp.getExpiresAt() != null && otp.getExpiresAt().isBefore(OffsetDateTime.now()))) {
            throw new InvalidOtpException(INVALID_OR_EXPIRED_MSG);
        }

        if (!otp.getCode().equals(command.getOtpCode())) {
            int attempts = otp.getAttempts() + 1;
            otp.setAttempts(attempts);
            if (attempts >= 5) {
                otp.setStatus(OtpStatus.EXPIRED);
                otpRepository.saveAndFlush(otp);
                throw new TooManyOtpAttemptsException(TOO_MANY_ATTEMPTS_MSG);
            }
            otpRepository.saveAndFlush(otp);
            throw new InvalidOtpException(INVALID_OR_EXPIRED_MSG);
        }

        otp.setStatus(OtpStatus.VERIFIED);
        otpRepository.saveAndFlush(otp);

        return issueTokens(user, request);
    }

    private AuthTokenResponseData issueTokens(User user, HttpServletRequest request) {
        String refreshToken = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expiresAt = now.plusDays(SESSION_TTL_DAYS);

        Session session = Session.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .refreshTokenHash(TokenHasher.hash(refreshToken))
                .ipAddress(resolveClientIp(request))
                .deviceInfo(request.getHeader("User-Agent"))
                .revoked(false)
                .createdAt(now)
                .updatedAt(now)
                .lastUsedAt(now)
                .expiresAt(expiresAt)
                .build();
        sessionRepository.saveAndFlush(session);

        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), session.getId());
        return AuthTokenResponseData.builder()
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresIn(JwtTokenProvider.ACCESS_TOKEN_TTL_SECONDS)
                .refreshToken(refreshToken)
                .refreshTokenExpiresIn(JwtTokenProvider.REFRESH_TOKEN_TTL_SECONDS)
                .build();
    }

    private InetAddress resolveClientIp(HttpServletRequest request) {
        try {
            String remoteAddr = request.getRemoteAddr();
            if (remoteAddr == null || remoteAddr.isBlank()) {
                return InetAddress.getLoopbackAddress();
            }
            return InetAddress.getByName(remoteAddr);
        } catch (Exception e) {
            return InetAddress.getLoopbackAddress();
        }
    }
}