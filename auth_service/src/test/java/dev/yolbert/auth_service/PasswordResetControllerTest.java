package dev.yolbert.auth_service;

import dev.yolbert.auth_service.config.JwtTokenProvider;
import dev.yolbert.auth_service.domain.entity.*;
import dev.yolbert.auth_service.repository.OtpRepository;
import dev.yolbert.auth_service.repository.SessionRepository;
import dev.yolbert.auth_service.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PasswordResetControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OtpRepository otpRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static final String REQUEST_ENDPOINT = "/auth/password-reset/request";
    private static final String CONFIRM_ENDPOINT = "/auth/password-reset/confirm";

    private User createTestUser(String email, boolean verified) {
        LocalDateTime now = LocalDateTime.now();
        User user = User.builder()
                .id(UUID.randomUUID())
                .fullName("Reset User")
                .email(email)
                .passwordHash(passwordEncoder.encode("OldPass@123!"))
                .friendCode("FC" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .createdAt(now)
                .updatedAt(now)
                .verifiedAt(verified ? now : null)
                .build();
        return userRepository.save(user);
    }

    private Session createSession(UUID userId) throws Exception {
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

    @Test
    void CA07_requestReset_existingUser_generatesOtp() throws Exception {
        String email = "reset-registered@example.com";
        createTestUser(email, true);

        String body = """
                {"email": "%s"}
                """.formatted(email);

        mockMvc.perform(post(REQUEST_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

        List<Otp> otps = otpRepository.findAll();
        assertThat(otps.stream().anyMatch(o -> o.getPurpose() == OtpPurpose.PASSWORD_RESET)).isTrue();
    }

    @Test
    void CA08_requestReset_unregisteredUser_responds200WithoutOtp() throws Exception {
        String email = "unregistered-reset@example.com";
        String body = """
                {"email": "%s"}
                """.formatted(email);

        long countBefore = otpRepository.count();

        mockMvc.perform(post(REQUEST_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

        assertThat(otpRepository.count()).isEqualTo(countBefore);
    }

    @Test
    void CA09_confirmReset_validOtp_updatesPasswordAndRevokesAllSessions() throws Exception {
        String email = "confirm-reset@example.com";
        User user = createTestUser(email, true);
        Session session1 = createSession(user.getId());
        Session session2 = createSession(user.getId());

        String otpCode = "123456";
        OffsetDateTime now = OffsetDateTime.now();
        Otp otp = Otp.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .code(otpCode)
                .purpose(OtpPurpose.PASSWORD_RESET)
                .attempts(0)
                .status(OtpStatus.PENDING)
                .createdAt(now)
                .expiresAt(now.plusMinutes(15))
                .build();
        otpRepository.save(otp);

        String body = """
                {
                  "email": "%s",
                  "otp_code": "%s",
                  "new_password": "NewSecretPass@123!",
                  "confirm_new_password": "NewSecretPass@123!"
                }
                """.formatted(email, otpCode);

        mockMvc.perform(post(CONFIRM_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

        User updatedUser = userRepository.findById(user.getId()).orElseThrow();
        assertThat(passwordEncoder.matches("NewSecretPass@123!", updatedUser.getPasswordHash())).isTrue();

        Session s1 = sessionRepository.findById(session1.getId()).orElseThrow();
        Session s2 = sessionRepository.findById(session2.getId()).orElseThrow();
        assertThat(s1.isRevoked()).isTrue();
        assertThat(s2.isRevoked()).isTrue();
    }

    @Test
    void CA09c_confirmReset_invalidatesPriorToken() throws Exception {
        String email = "invalidatetoken@example.com";
        User user = createTestUser(email, true);
        Session session = createSession(user.getId());
        String priorAccessToken = jwtTokenProvider.generateAccessToken(user.getId(), session.getId());

        // Prior token works before reset
        mockMvc.perform(get("/auth/ping").header("Authorization", "Bearer " + priorAccessToken))
                .andExpect(status().isOk());

        // Create OTP and confirm reset
        String otpCode = "654321";
        OffsetDateTime now = OffsetDateTime.now();
        Otp otp = Otp.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .code(otpCode)
                .purpose(OtpPurpose.PASSWORD_RESET)
                .attempts(0)
                .status(OtpStatus.PENDING)
                .createdAt(now)
                .expiresAt(now.plusMinutes(15))
                .build();
        otpRepository.save(otp);

        String body = """
                {
                  "email": "%s",
                  "otp_code": "%s",
                  "new_password": "ResetPass@999!",
                  "confirm_new_password": "ResetPass@999!"
                }
                """.formatted(email, otpCode);

        mockMvc.perform(post(CONFIRM_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Prior token is rejected after reset (401 Unauthorized)
        mockMvc.perform(get("/auth/ping").header("Authorization", "Bearer " + priorAccessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void CA10_confirmReset_wrongOtp_and_maxAttempts() throws Exception {
        String email = "wrongotp@example.com";
        User user = createTestUser(email, true);

        String otpCode = "111111";
        OffsetDateTime now = OffsetDateTime.now();
        Otp otp = Otp.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .code(otpCode)
                .purpose(OtpPurpose.PASSWORD_RESET)
                .attempts(4)
                .status(OtpStatus.PENDING)
                .createdAt(now)
                .expiresAt(now.plusMinutes(15))
                .build();
        otpRepository.save(otp);

        String bodyWrong = """
                {
                  "email": "%s",
                  "otp_code": "999999",
                  "new_password": "NewPass@12345!",
                  "confirm_new_password": "NewPass@12345!"
                }
                """.formatted(email);

        // 5th attempt with wrong code -> 429 Too Many Requests
        mockMvc.perform(post(CONFIRM_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWrong))
                .andExpect(status().isTooManyRequests());
    }
}
