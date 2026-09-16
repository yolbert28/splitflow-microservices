package dev.yolbert.auth_service;

import dev.yolbert.auth_service.domain.entity.Otp;
import dev.yolbert.auth_service.domain.entity.OtpPurpose;
import dev.yolbert.auth_service.domain.entity.OtpStatus;
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
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthControllerLogin2faTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OtpRepository otpRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    private static final String VERIFY_2FA_ENDPOINT = "/auth/login/verify-2fa";
    private static final String TEST_EMAIL = "ana.torres@example.com";
    private static final String TEST_OTP = "482913";

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

    private User createVerifiedUser(String email) {
        User user = User.builder()
                .id(UUID.randomUUID())
                .fullName("Ana María Torres")
                .email(email)
                .passwordHash("hashed_password")
                .friendCode("AMTORRES12")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .verifiedAt(LocalDateTime.now())
                .build();
        return userRepository.save(user);
    }

    private Otp createLoginOtp(UUID userId, String code, int attempts, OtpStatus status) {
        Otp otp = Otp.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .code(code)
                .purpose(OtpPurpose.LOGIN_2FA)
                .attempts(attempts)
                .status(status)
                .createdAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusMinutes(15))
                .build();
        return otpRepository.save(otp);
    }

    private String buildBody(String email, String otpCode) {
        return """
                {
                  "email": %s,
                  "otp_code": %s
                }
                """.formatted(
                email != null ? "\"" + email + "\"" : "null",
                otpCode != null ? "\"" + otpCode + "\"" : "null"
        );
    }

    @Test
    void verify2fa_success() throws Exception {
        User user = createVerifiedUser(TEST_EMAIL);
        createLoginOtp(user.getId(), TEST_OTP, 0, OtpStatus.PENDING);

        String response = mockMvc.perform(post(VERIFY_2FA_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody(TEST_EMAIL, TEST_OTP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("Autenticación completada."))
                .andExpect(jsonPath("$.data.access_token").isNotEmpty())
                .andExpect(jsonPath("$.data.token_type").value("Bearer"))
                .andExpect(jsonPath("$.data.expires_in").value(3600))
                .andExpect(jsonPath("$.data.refresh_token").isNotEmpty())
                .andExpect(jsonPath("$.data.refresh_token_expires_in").value(604800))
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(response).path("data");
        String accessToken = data.path("access_token").asText();
        String refreshToken = data.path("refresh_token").asText();

        List<Session> sessions = sessionRepository.findAll();
        assertThat(sessions).hasSize(1);
        Session session = sessions.get(0);
        assertThat(session.getUserId()).isEqualTo(user.getId());
        assertThat(session.isRevoked()).isFalse();
        assertThat(session.getExpiresAt()).isAfter(OffsetDateTime.now());
        assertThat(session.getRefreshTokenHash()).isNotEqualTo(refreshToken);

        Otp updatedOtp = otpRepository.findById(
                otpRepository.findTopByUserIdAndPurposeOrderByCreatedAtDesc(user.getId(), OtpPurpose.LOGIN_2FA)
                        .orElseThrow().getId()).orElseThrow();
        assertThat(updatedOtp.getStatus()).isEqualTo(OtpStatus.VERIFIED);

        String payloadJson = new String(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]), StandardCharsets.UTF_8);
        JsonNode claims = objectMapper.readTree(payloadJson);
        assertThat(claims.path("sub").asText()).isEqualTo(user.getId().toString());
        long exp = claims.path("exp").asLong();
        long nowEpoch = OffsetDateTime.now().toEpochSecond();
        assertThat(exp).isBetween(nowEpoch + 3500, nowEpoch + 3700);
    }

    @Test
    void verify2fa_wrongCode() throws Exception {
        User user = createVerifiedUser(TEST_EMAIL);
        createLoginOtp(user.getId(), TEST_OTP, 0, OtpStatus.PENDING);

        mockMvc.perform(post(VERIFY_2FA_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody(TEST_EMAIL, "000000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("El código ingresado es inválido o ha expirado."));

        Otp updatedOtp = otpRepository.findAll().get(0);
        assertThat(updatedOtp.getAttempts()).isEqualTo(1);
        assertThat(updatedOtp.getStatus()).isEqualTo(OtpStatus.PENDING);
    }

    @Test
    void verify2fa_tooManyAttempts() throws Exception {
        User user = createVerifiedUser(TEST_EMAIL);
        createLoginOtp(user.getId(), TEST_OTP, 4, OtpStatus.PENDING);

        mockMvc.perform(post(VERIFY_2FA_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody(TEST_EMAIL, "000000")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Demasiados intentos. Intenta de nuevo en unos minutos."));

        Otp updatedOtp = otpRepository.findAll().get(0);
        assertThat(updatedOtp.getAttempts()).isEqualTo(5);
        assertThat(updatedOtp.getStatus()).isEqualTo(OtpStatus.EXPIRED);
    }

    @Test
    void verify2fa_expiredOtp() throws Exception {
        User user = createVerifiedUser(TEST_EMAIL);
        Otp expired = createLoginOtp(user.getId(), TEST_OTP, 0, OtpStatus.PENDING);
        expired.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        otpRepository.save(expired);

        mockMvc.perform(post(VERIFY_2FA_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody(TEST_EMAIL, TEST_OTP)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El código ingresado es inválido o ha expirado."));
    }

    @Test
    void verify2fa_unknownEmail() throws Exception {
        mockMvc.perform(post(VERIFY_2FA_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody("no.existe@example.com", TEST_OTP)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El código ingresado es inválido o ha expirado."));
    }
}