package dev.yolbert.auth_service.config;

import dev.yolbert.auth_service.repository.SessionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Spring Security filter that validates the {@code Authorization: Bearer <token>}
 * header, verifies that its underlying session (claim {@code sid}) is active,
 * and populates the {@code SecurityContext} for the request.
 *
 * <p>Invalid, revoked, or missing-session tokens are left unauthenticated; Spring Security then
 * rejects the request with 401 when the route requires authentication.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final SessionRepository sessionRepository;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider, SessionRepository sessionRepository) {
        this.jwtTokenProvider  = jwtTokenProvider;
        this.sessionRepository = sessionRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            if (jwtTokenProvider.validateToken(token)) {
                UUID userId = jwtTokenProvider.getUserIdFromToken(token);
                UUID sessionId = jwtTokenProvider.getSessionIdFromToken(token);

                if (sessionId != null && isSessionActive(sessionId)) {
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(userId, null, List.of());
                    authentication.setDetails(sessionId);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } else {
                    SecurityContextHolder.clearContext();
                }
            } else {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean isSessionActive(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .map(session -> !session.isRevoked())
                .orElse(false);
    }
}