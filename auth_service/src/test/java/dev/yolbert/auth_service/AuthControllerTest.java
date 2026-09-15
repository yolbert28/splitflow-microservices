package dev.yolbert.auth_service;

import dev.yolbert.auth_service.domain.entity.Otp;
import dev.yolbert.auth_service.domain.entity.OtpPurpose;
import dev.yolbert.auth_service.domain.entity.OtpStatus;
import dev.yolbert.auth_service.domain.entity.User;
import dev.yolbert.auth_service.repository.OtpRepository;
import dev.yolbert.auth_service.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OtpRepository otpRepository;

    private static final String VERIFY_ENDPOINT = "/auth/verify-email";
    private static final String TEST_EMAIL = "ana.torres@example.com";
    private static final String TEST_OTP = "482913";

    @BeforeEach
    void setUp() {
        otpRepository.deleteAll();
        userRepository.deleteAll();
    }

    private User createTestUser(String email) {
        User user = User.builder()
                .id(UUID.randomUUID())
                .fullName("Ana María Torres")
                .email(email)
                .passwordHash("hashed_password")
                .friendCode("AMTORRES12")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        return userRepository.save(user);
    }

    private Otp createTestOtp(UUID userId, String code, int attempts, OtpStatus status) {
        Otp otp = Otp.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .code(code)
                .purpose(OtpPurpose.EMAIL_VERIFICATION)
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
    void verifyEmail_success() throws Exception {
        User user = createTestUser(TEST_EMAIL);
        createTestOtp(user.getId(), TEST_OTP, 0, OtpStatus.PENDING);

        String body = buildBody(TEST_EMAIL, TEST_OTP);

        mockMvc.perform(post(VERIFY_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("Correo verificado exitosamente."))
                .andExpect(jsonPath("$.data.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.data.email").value(TEST_EMAIL))
                .andExpect(jsonPath("$.data.verified").value(true))
                .andExpect(jsonPath("$.data.verified_at").isNotEmpty());

        User updatedUser = userRepository.findById(user.getId()).orElseThrow();
        assertThat(updatedUser.getVerifiedAt()).isNotNull();

        Otp updatedOtp = otpRepository.findAll().get(0);
        assertThat(updatedOtp.getStatus()).isEqualTo(OtpStatus.VERIFIED);
    }

    @Test
    void verifyEmail_incorrectOtp() throws Exception {
        User user = createTestUser(TEST_EMAIL);
        createTestOtp(user.getId(), TEST_OTP, 0, OtpStatus.PENDING);

        String body = buildBody(TEST_EMAIL, "000000");

        mockMvc.perform(post(VERIFY_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("El código ingresado es inválido o ha expirado."))
                .andExpect(jsonPath("$.errors[0].field").value("otp_code"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_OR_EXPIRED"))
                .andExpect(jsonPath("$.errors[0].message").value("El código ingresado es inválido o ha expirado."));

        Otp updatedOtp = otpRepository.findAll().get(0);
        assertThat(updatedOtp.getAttempts()).isEqualTo(1);
        assertThat(updatedOtp.getStatus()).isEqualTo(OtpStatus.PENDING);
    }

    @Test
    void verifyEmail_missingEmail() throws Exception {
        String body = buildBody(null, TEST_OTP);

        mockMvc.perform(post(VERIFY_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void verifyEmail_missingOtpCode() throws Exception {
        String body = buildBody(TEST_EMAIL, null);

        mockMvc.perform(post(VERIFY_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void verifyEmail_tooManyAttempts_fifthFailure() throws Exception {
        User user = createTestUser(TEST_EMAIL);
        createTestOtp(user.getId(), TEST_OTP, 4, OtpStatus.PENDING);

        String body = buildBody(TEST_EMAIL, "000000");

        mockMvc.perform(post(VERIFY_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Demasiados intentos. Intenta de nuevo en unos minutos."));

        Otp updatedOtp = otpRepository.findAll().get(0);
        assertThat(updatedOtp.getAttempts()).isEqualTo(5);
        assertThat(updatedOtp.getStatus()).isEqualTo(OtpStatus.EXPIRED);
    }
}
