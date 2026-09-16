package dev.yolbert.auth_service;

import dev.yolbert.auth_service.config.JwtTokenProvider;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LogoutControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OtpRepository otpRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private SessionRepository sessionRepository;

    private static final String LOGOUT_ENDPOINT = "/auth/logout";
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

    private Session createSession(UUID userId, String refreshToken, boolean revoked) throws Exception {
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
                .expiresAt(now.plusDays(7))
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
    void logout_success() throws Exception {
        User user = createUser();
        Session session = createSession(user.getId(), TEST_REFRESH_TOKEN, false);
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId());

        mockMvc.perform(post(LOGOUT_ENDPOINT)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody(TEST_REFRESH_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("Sesión cerrada exitosamente."));

        Session updated = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(updated.isRevoked()).isTrue();
        assertThat(updated.getRevokedAt()).isNotNull();
    }

    @Test
    void logout_alreadyRevoked() throws Exception {
        User user = createUser();
        createSession(user.getId(), TEST_REFRESH_TOKEN, true);
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId());

        mockMvc.perform(post(LOGOUT_ENDPOINT)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody(TEST_REFRESH_TOKEN)))
                .andExpect(status().isOk());
    }

    @Test
    void logout_unknownToken() throws Exception {
        User user = createUser();
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId());

        mockMvc.perform(post(LOGOUT_ENDPOINT)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody("unknown-token-value")))
                .andExpect(status().isOk());
    }

    @Test
    void logout_withoutToken() throws Exception {
        mockMvc.perform(post(LOGOUT_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody(TEST_REFRESH_TOKEN)))
                .andExpect(status().isUnauthorized());
    }
}