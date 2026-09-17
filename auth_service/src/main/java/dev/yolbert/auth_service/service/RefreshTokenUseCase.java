package dev.yolbert.auth_service.service;

import dev.yolbert.auth_service.config.JwtTokenProvider;
import dev.yolbert.auth_service.domain.entity.Session;
import dev.yolbert.auth_service.dto.AuthTokenResponseData;
import dev.yolbert.auth_service.dto.RefreshTokenCommand;
import dev.yolbert.auth_service.repository.SessionRepository;
import dev.yolbert.auth_service.utils.TokenHasher;
import dev.yolbert.auth_service.utils.exceptions.SessionNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class RefreshTokenUseCase {

    private static final String SESSION_INVALID_MSG = "Sesión inválida o expirada.";
    private static final long SESSION_TTL_DAYS = 7;

    private final SessionRepository sessionRepository;
    private final JwtTokenProvider jwtTokenProvider;

    public RefreshTokenUseCase(SessionRepository sessionRepository,
                               JwtTokenProvider jwtTokenProvider) {
        this.sessionRepository = sessionRepository;
        this.jwtTokenProvider  = jwtTokenProvider;
    }

    /**
     * Validates the refresh token with an indexed SHA-256 hash lookup,
     * rotates the session and issues a new token pair.
     *
     * <p>The token is hashed with SHA-256 before querying the database, which
     * allows the query to use the {@code idx_session_refresh_token_hash} index
     * instead of loading all sessions and performing a per-row comparison.
     */
    @Transactional
    public AuthTokenResponseData execute(RefreshTokenCommand command, HttpServletRequest request) {
        Session current = findActiveSession(command.getRefreshToken())
                .orElseThrow(() -> new SessionNotFoundException(SESSION_INVALID_MSG));

        OffsetDateTime now = OffsetDateTime.now();
        current.setRevoked(true);
        current.setRevokedAt(now);
        current.setUpdatedAt(now);
        sessionRepository.save(current);

        String newRefreshToken = UUID.randomUUID().toString();

        Session newSession = Session.builder()
                .id(UUID.randomUUID())
                .userId(current.getUserId())
                .refreshTokenHash(TokenHasher.hash(newRefreshToken))
                .ipAddress(current.getIpAddress())
                .deviceInfo(current.getDeviceInfo())
                .revoked(false)
                .createdAt(now)
                .updatedAt(now)
                .lastUsedAt(now)
                .expiresAt(now.plusDays(SESSION_TTL_DAYS))
                .build();
        sessionRepository.save(newSession);

        String accessToken = jwtTokenProvider.generateAccessToken(newSession.getUserId(), newSession.getId());
        return AuthTokenResponseData.builder()
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresIn(JwtTokenProvider.ACCESS_TOKEN_TTL_SECONDS)
                .refreshToken(newRefreshToken)
                .refreshTokenExpiresIn(JwtTokenProvider.REFRESH_TOKEN_TTL_SECONDS)
                .build();
    }

    Optional<Session> findActiveSession(String refreshToken) {
        return sessionRepository.findByRefreshTokenHash(TokenHasher.hash(refreshToken))
                .filter(session -> !session.isRevoked())
                .filter(session -> session.getExpiresAt() == null
                        || session.getExpiresAt().isAfter(OffsetDateTime.now()));
    }
}