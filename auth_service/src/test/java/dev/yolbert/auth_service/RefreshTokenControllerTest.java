package dev.yolbert.auth_service;

import dev.yolbert.auth_service.domain.entity.Session;
import dev.yolbert.auth_service.domain.entity.User;
import dev.yolbert.auth_service.repository.OtpRepository;
import dev.yolbert.auth_service.repository.OutboxRepository;
import dev.yolbert.auth_service.repository.SessionRepository;
import dev.yolbert.auth_service.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import dev.yolbert.auth_service.utils.TokenHasher;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RefreshTokenControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OtpRepository otpRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private SessionRepository sessionRepository;

    private static final String REFRESH_ENDPOINT = "/auth/refresh";
    private static final String TEST_EMAIL = "ana.torres@example.com";
    private static final String TEST_REFRESH_TOKEN = "5f0f2d6e-7a41-4f39-b27d-2f20d2500f01";

    @BeforeEach
    void setUp() {
        sessionRepository.deleteAll();
        otpRepository.deleteAll();
        outboxRepository.deleteAll();
        userRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        sessionRepository.deleteAll();
        otpRepository.deleteAll();
        outboxRepository.deleteAll();
        userRepository.deleteAll();
    }

    private User createUser() {
        User user = User.builder()
                .id(UUID.randomUUID())
                .fullName("Ana María Torres")
                .email(TEST_EMAIL)
                .passwordHash("hashed_password")
                .friendCode("AMTORRES12")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .verifiedAt(LocalDateTime.now())
                .build();
        return userRepository.save(user);
    }

    private Session createSession(UUID userId, String refreshToken, boolean revoked, OffsetDateTime expiresAt) throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Session session = Session.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .refreshTokenHash(TokenHasher.hash(refreshToken))
                .ipAddress(InetAddress.getLoopbackAddress())
                .deviceInfo("test-client")
                .revoked(revoked)
                .createdAt(now)
                .updatedAt(now)
                .lastUsedAt(now)
                .expiresAt(expiresAt)
                .build();
        return sessionRepository.save(session);
    }

    private String buildBody(String refreshToken) {
        return """
                {
                  "refresh_token": %s
                }
                """.formatted(refreshToken != null ? "\"" + refreshToken + "\"" : "null");
    }

    @Test
    void refresh_success() throws Exception {
        User user = createUser();
        Session oldSession = createSession(user.getId(), TEST_REFRESH_TOKEN, false, OffsetDateTime.now().plusDays(7));

        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody(TEST_REFRESH_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.access_token").isNotEmpty())
                .andExpect(jsonPath("$.data.token_type").value("Bearer"))
                .andExpect(jsonPath("$.data.expires_in").value(3600))
                .andExpect(jsonPath("$.data.refresh_token").isNotEmpty())
                .andExpect(jsonPath("$.data.refresh_token_expires_in").value(604800));

        Session revoked = sessionRepository.findById(oldSession.getId()).orElseThrow();
        assertThat(revoked.isRevoked()).isTrue();
        assertThat(revoked.getRevokedAt()).isNotNull();

        List<Session> sessions = sessionRepository.findAll();
        assertThat(sessions).hasSize(2);
        Session newSession = sessions.stream().filter(s -> !s.isRevoked()).findFirst().orElseThrow();
        assertThat(newSession.getUserId()).isEqualTo(user.getId());
        assertThat(newSession.getRefreshTokenHash()).isNotEqualTo(TEST_REFRESH_TOKEN);
    }

    @Test
    void refresh_revokedToken() throws Exception {
        User user = createUser();
        createSession(user.getId(), TEST_REFRESH_TOKEN, true, OffsetDateTime.now().plusDays(7));

        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody(TEST_REFRESH_TOKEN)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Sesión inválida o expirada."));
    }

    @Test
    void refresh_expiredToken() throws Exception {
        User user = createUser();
        createSession(user.getId(), TEST_REFRESH_TOKEN, false, OffsetDateTime.now().minusDays(1));

        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody(TEST_REFRESH_TOKEN)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Sesión inválida o expirada."));
    }

    @Test
    void refresh_unknownToken() throws Exception {
        mockMvc.perform(post(REFRESH_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody("unknown-token-value")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Sesión inválida o expirada."));
    }
}