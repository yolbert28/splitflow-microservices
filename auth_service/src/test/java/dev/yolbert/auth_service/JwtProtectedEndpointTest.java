package dev.yolbert.auth_service;

import dev.yolbert.auth_service.config.JwtTokenProvider;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class JwtProtectedEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Value("${jwt.private-key}")
    private String privateKeyPem;

    @Autowired
    private dev.yolbert.auth_service.repository.UserRepository userRepository;

    @Autowired
    private dev.yolbert.auth_service.repository.SessionRepository sessionRepository;

    private static final String PING_ENDPOINT = "/auth/ping";

    @Test
    void accessWithValidToken() throws Exception {
        java.util.UUID userId = java.util.UUID.randomUUID();
        java.time.LocalDateTime nowLocal = java.time.LocalDateTime.now();
        userRepository.save(dev.yolbert.auth_service.domain.entity.User.builder()
                .id(userId)
                .fullName("Test User")
                .email("test-" + userId + "@example.com")
                .passwordHash("hash")
                .friendCode("FC" + userId.toString().substring(0, 8).toUpperCase())
                .createdAt(nowLocal)
                .updatedAt(nowLocal)
                .build());

        java.util.UUID sessionId = java.util.UUID.randomUUID();
        sessionRepository.save(dev.yolbert.auth_service.domain.entity.Session.builder()
                .id(sessionId)
                .userId(userId)
                .ipAddress(java.net.InetAddress.getLoopbackAddress())
                .refreshTokenHash("dummy-hash-" + sessionId)
                .revoked(false)
                .createdAt(java.time.OffsetDateTime.now())
                .updatedAt(java.time.OffsetDateTime.now())
                .lastUsedAt(java.time.OffsetDateTime.now())
                .expiresAt(java.time.OffsetDateTime.now().plusDays(7))
                .build());

        String token = jwtTokenProvider.generateAccessToken(userId, sessionId);

        mockMvc.perform(get(PING_ENDPOINT).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void accessWithExpiredToken() throws Exception {
        String token = buildExpiredToken();

        mockMvc.perform(get(PING_ENDPOINT).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void accessWithMangledToken() throws Exception {
        java.util.UUID userId = java.util.UUID.randomUUID();
        java.time.LocalDateTime nowLocal = java.time.LocalDateTime.now();
        userRepository.save(dev.yolbert.auth_service.domain.entity.User.builder()
                .id(userId)
                .fullName("Test User")
                .email("test-" + userId + "@example.com")
                .passwordHash("hash")
                .friendCode("FC" + userId.toString().substring(0, 8).toUpperCase())
                .createdAt(nowLocal)
                .updatedAt(nowLocal)
                .build());

        java.util.UUID sessionId = java.util.UUID.randomUUID();
        sessionRepository.save(dev.yolbert.auth_service.domain.entity.Session.builder()
                .id(sessionId)
                .userId(userId)
                .ipAddress(java.net.InetAddress.getLoopbackAddress())
                .refreshTokenHash("dummy-hash-" + sessionId)
                .revoked(false)
                .createdAt(java.time.OffsetDateTime.now())
                .updatedAt(java.time.OffsetDateTime.now())
                .lastUsedAt(java.time.OffsetDateTime.now())
                .expiresAt(java.time.OffsetDateTime.now().plusDays(7))
                .build());

        String valid = jwtTokenProvider.generateAccessToken(userId, sessionId);
        String mangled = valid.substring(0, valid.length() - 2) + "aa";

        mockMvc.perform(get(PING_ENDPOINT).header("Authorization", "Bearer " + mangled))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void accessWithoutToken() throws Exception {
        mockMvc.perform(get(PING_ENDPOINT))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void CA17_newProtectedEndpoints_unauthenticated_return401() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/auth/user/me"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/auth/user/me/password"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/auth/user/me/friend-code"))
                .andExpect(status().isUnauthorized());
    }

    private String buildExpiredToken() throws Exception {
        byte[] der = Base64.getDecoder().decode(privateKeyPem.replaceAll("\\s", ""));
        PrivateKey privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(der));
        return Jwts.builder()
                .subject("00000000-0000-0000-0000-000000000000")
                .issuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .expiration(Date.from(Instant.now().minusSeconds(3600)))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }
}