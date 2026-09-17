package dev.yolbert.auth_service;

import dev.yolbert.auth_service.domain.entity.Otp;
import dev.yolbert.auth_service.domain.entity.OtpPurpose;
import dev.yolbert.auth_service.domain.entity.OtpStatus;
import dev.yolbert.auth_service.domain.entity.User;
import dev.yolbert.auth_service.repository.OtpRepository;
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ResendVerificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OtpRepository otpRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static final String ENDPOINT = "/auth/resend-verification";

    private User createTestUser(String email, boolean verified) {
        LocalDateTime now = LocalDateTime.now();
        User user = User.builder()
                .id(UUID.randomUUID())
                .fullName("Resend User")
                .email(email)
                .passwordHash(passwordEncoder.encode("Pass@12345!"))
                .friendCode("FC" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .createdAt(now)
                .updatedAt(now)
                .verifiedAt(verified ? now : null)
                .build();
        return userRepository.save(user);
    }

    @Test
    void CA18_resendVerification_unverifiedUser_generatesNewOtp() throws Exception {
        String email = "unverified-resend@example.com";
        User user = createTestUser(email, false);

        OffsetDateTime now = OffsetDateTime.now();
        Otp oldOtp = Otp.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .code("111111")
                .purpose(OtpPurpose.EMAIL_VERIFICATION)
                .attempts(0)
                .status(OtpStatus.PENDING)
                .createdAt(now.minusMinutes(5))
                .expiresAt(now.plusMinutes(10))
                .build();
        otpRepository.save(oldOtp);

        String body = """
                {"email": "%s"}
                """.formatted(email);

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

        Otp updatedOldOtp = otpRepository.findById(oldOtp.getId()).orElseThrow();
        assertThat(updatedOldOtp.getStatus()).isEqualTo(OtpStatus.EXPIRED);

        List<Otp> userOtps = otpRepository.findAll().stream()
                .filter(o -> o.getUserId().equals(user.getId()) && o.getStatus() == OtpStatus.PENDING)
                .toList();
        assertThat(userOtps).hasSize(1);
        assertThat(userOtps.get(0).getCode()).isNotEqualTo("111111");
    }

    @Test
    void CA19_resendVerification_alreadyVerifiedUser_responds200NoNewOtp() throws Exception {
        String email = "verified-resend@example.com";
        createTestUser(email, true);

        long otpCountBefore = otpRepository.count();

        String body = """
                {"email": "%s"}
                """.formatted(email);

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

        assertThat(otpRepository.count()).isEqualTo(otpCountBefore);
    }
}
