package dev.yolbert.auth_service;

import dev.yolbert.auth_service.config.LoginRateLimitFilter;
import dev.yolbert.auth_service.domain.entity.Otp;
import dev.yolbert.auth_service.domain.entity.OtpPurpose;
import dev.yolbert.auth_service.domain.entity.OtpStatus;
import dev.yolbert.auth_service.domain.entity.Outbox;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthControllerLoginTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OtpRepository otpRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private LoginRateLimitFilter loginRateLimitFilter;

    private static final String LOGIN_ENDPOINT = "/auth/login";
    private static final String TEST_EMAIL = "ana.torres@example.com";
    private static final String TEST_PASSWORD = "Secure@123!";

    @BeforeEach
    void setUp() {
        sessionRepository.deleteAll();
        otpRepository.deleteAll();
        outboxRepository.deleteAll();
        userRepository.deleteAll();
        loginRateLimitFilter.reset();
    }

    @AfterEach
    void tearDown() {
        sessionRepository.deleteAll();
        otpRepository.deleteAll();
        outboxRepository.deleteAll();
        userRepository.deleteAll();
    }

    private User createTestUser(String email, boolean verified) {
        User user = User.builder()
                .id(UUID.randomUUID())
                .fullName("Ana María Torres")
                .email(email)
                .passwordHash(passwordEncoder.encode(TEST_PASSWORD))
                .friendCode("AMTORRES12")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .verifiedAt(verified ? LocalDateTime.now() : null)
                .build();
        return userRepository.save(user);
    }

    private String buildBody(String email, String password) {
        return """
                {
                  "email": %s,
                  "password": %s
                }
                """.formatted(
                email != null ? "\"" + email + "\"" : "null",
                password != null ? "\"" + password + "\"" : "null"
        );
    }

    @Test
    void login_success() throws Exception {
        User user = createTestUser(TEST_EMAIL, true);

        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody(TEST_EMAIL, TEST_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("Se ha enviado un código de verificación a tu correo."));

        List<Otp> otps = otpRepository.findAll();
        assertThat(otps).hasSize(1);
        Otp otp = otps.get(0);
        assertThat(otp.getUserId()).isEqualTo(user.getId());
        assertThat(otp.getPurpose()).isEqualTo(OtpPurpose.LOGIN_2FA);
        assertThat(otp.getStatus()).isEqualTo(OtpStatus.PENDING);
        assertThat(otp.getCode()).matches("[0-9]{6}");
        assertThat(otp.getExpiresAt()).isBeforeOrEqualTo(otp.getCreatedAt().plusMinutes(15));

        List<Outbox> events = outboxRepository.findAll();
        assertThat(events).hasSize(1);
        Outbox event = events.get(0);
        assertThat(event.getEventType()).isEqualTo("USER_LOGIN_OTP");
        assertThat(event.getAggregateId()).isEqualTo(user.getId());

        JsonNode payload = objectMapper.readTree(event.getPayload());
        assertThat(payload.path("id").asText()).isEqualTo(user.getId().toString());
        assertThat(payload.path("email").asText()).isEqualTo(TEST_EMAIL);
        assertThat(payload.path("otp_code").asText()).isEqualTo(otp.getCode());
    }

    @Test
    void login_wrongPassword() throws Exception {
        createTestUser(TEST_EMAIL, true);

        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody(TEST_EMAIL, "WrongPassword!")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Credenciales inválidas."));
    }

    @Test
    void login_unknownEmail() throws Exception {
        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody("no.existe@example.com", TEST_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Credenciales inválidas."));
    }

    @Test
    void login_unverifiedUser() throws Exception {
        createTestUser(TEST_EMAIL, false);

        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody(TEST_EMAIL, TEST_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Credenciales inválidas."));
    }

    @Test
    void login_deletedAccount_returnsGenericError() throws Exception {
        User user = createTestUser(TEST_EMAIL, true);
        user.setDeletedAt(LocalDateTime.now());
        userRepository.save(user);

        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody(TEST_EMAIL, TEST_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Credenciales inválidas."));

        List<Otp> otps = otpRepository.findAll();
        assertThat(otps).isEmpty();
    }

    @Test
    void login_invalidatesPreviousPendingOtp() throws Exception {
        createTestUser(TEST_EMAIL, true);

        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody(TEST_EMAIL, TEST_PASSWORD)))
                .andExpect(status().isOk());

        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody(TEST_EMAIL, TEST_PASSWORD)))
                .andExpect(status().isOk());

        List<Otp> otps = otpRepository.findAll();
        assertThat(otps).hasSize(2);
        long expired = otps.stream().filter(otp -> otp.getStatus() == OtpStatus.EXPIRED).count();
        long pending = otps.stream().filter(otp -> otp.getStatus() == OtpStatus.PENDING).count();
        assertThat(expired).isEqualTo(1);
        assertThat(pending).isEqualTo(1);
    }
}