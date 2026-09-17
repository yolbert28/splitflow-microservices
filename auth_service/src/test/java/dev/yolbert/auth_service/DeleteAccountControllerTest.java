package dev.yolbert.auth_service;

import dev.yolbert.auth_service.config.JwtTokenProvider;
import dev.yolbert.auth_service.domain.entity.Outbox;
import dev.yolbert.auth_service.domain.entity.Session;
import dev.yolbert.auth_service.domain.entity.User;
import dev.yolbert.auth_service.repository.OutboxRepository;
import dev.yolbert.auth_service.repository.OtpRepository;
import dev.yolbert.auth_service.repository.SessionRepository;
import dev.yolbert.auth_service.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeleteAccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private OtpRepository otpRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private static final String ENDPOINT = "/auth/user/me";

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

    private User createTestUser(String email) {
        LocalDateTime now = LocalDateTime.now();
        User user = User.builder()
                .id(UUID.randomUUID())
                .fullName("Ana María Torres")
                .email(email)
                .passwordHash("hashed-password")
                .friendCode("FC" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .createdAt(now)
                .updatedAt(now)
                .verifiedAt(now)
                .build();
        return userRepository.save(user);
    }

    private Session createSession(UUID userId) {
        OffsetDateTime now = OffsetDateTime.now();
        Session session = Session.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .ipAddress(InetAddress.getLoopbackAddress())
                .refreshTokenHash("hash-" + UUID.randomUUID())
                .revoked(false)
                .createdAt(now)
                .updatedAt(now)
                .lastUsedAt(now)
                .expiresAt(now.plusDays(7))
                .build();
        return sessionRepository.save(session);
    }

    private String uniqueEmail() {
        return "delete-" + UUID.randomUUID() + "@example.com";
    }

    // ─── CA-01 / CA-05 / CA-06: borrado exitoso ─────────────────────────────────

    /**
     * CA-01, CA-05, CA-06 — 200 OK, deleted_at poblado, sesiones revocadas,
     * evento USER_DELETED en el outbox y cuerpo sin datos de usuario.
     */
    @Test
    void deleteAccount_success() throws Exception {
        User user = createTestUser(uniqueEmail());
        Session sessionA = createSession(user.getId());
        Session sessionB = createSession(user.getId());
        String token = jwtTokenProvider.generateAccessToken(user.getId(), sessionA.getId());

        mockMvc.perform(delete(ENDPOINT).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("Cuenta eliminada exitosamente."))
                .andExpect(jsonPath("$.data").doesNotExist());

        User saved = userRepository.findById(user.getId()).orElseThrow();
        assertThat(saved.getDeletedAt()).isNotNull();

        Session savedA = sessionRepository.findById(sessionA.getId()).orElseThrow();
        Session savedB = sessionRepository.findById(sessionB.getId()).orElseThrow();
        assertThat(savedA.isRevoked()).isTrue();
        assertThat(savedA.getRevokedAt()).isNotNull();
        assertThat(savedB.isRevoked()).isTrue();

        List<Outbox> deletedEvents = outboxRepository.findAll().stream()
                .filter(e -> e.getEventType().equals("USER_DELETED")
                        && e.getAggregateId().equals(user.getId()))
                .toList();
        assertThat(deletedEvents).hasSize(1);

        JsonNode payload = objectMapper.readTree(deletedEvents.get(0).getPayload());
        assertThat(payload.path("id").asText()).isEqualTo(user.getId().toString());
        assertThat(payload.path("email").asText()).isEqualTo(user.getEmail());
    }

    // ─── CA-03: el token previo se rechaza tras el borrado ──────────────────────

    /**
     * CA-03 / T-12 — tras eliminar la cuenta, el access token anterior es
     * rechazado (401) en un endpoint protegido porque su sesión quedó revocada.
     */
    @Test
    void deleteAccount_thenPreviousTokenRejected() throws Exception {
        User user = createTestUser(uniqueEmail());
        Session session = createSession(user.getId());
        String token = jwtTokenProvider.generateAccessToken(user.getId(), session.getId());

        mockMvc.perform(delete(ENDPOINT).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/auth/ping").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    // ─── CA-07: sin token → 401 ────────────────────────────────────────────────

    /**
     * CA-07 — request sin Authorization → 401 Unauthorized.
     */
    @Test
    void deleteAccount_withoutToken_unauthorized() throws Exception {
        mockMvc.perform(delete(ENDPOINT))
                .andExpect(status().isUnauthorized());
    }

    // ─── CA-08: cuenta ya eliminada → 200 idempotente ──────────────────────────

    /**
     * CA-08 — si la cuenta ya estaba eliminada pero la sesión sigue activa,
     * el endpoint responde 200 sin error y sin duplicar el evento.
     */
    @Test
    void deleteAccount_alreadyDeleted_idempotent() throws Exception {
        User user = createTestUser(uniqueEmail());
        user.setDeletedAt(LocalDateTime.now().minusDays(1));
        userRepository.save(user);

        Session session = createSession(user.getId());
        String token = jwtTokenProvider.generateAccessToken(user.getId(), session.getId());

        mockMvc.perform(delete(ENDPOINT).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Cuenta eliminada exitosamente."));

        User saved = userRepository.findById(user.getId()).orElseThrow();
        assertThat(saved.getDeletedAt()).isNotNull();

        List<Outbox> deletedEvents = outboxRepository.findAll().stream()
                .filter(e -> e.getEventType().equals("USER_DELETED")
                        && e.getAggregateId().equals(user.getId()))
                .toList();
        assertThat(deletedEvents).isEmpty();

        Session savedSession = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(savedSession.isRevoked()).isFalse();
    }
}