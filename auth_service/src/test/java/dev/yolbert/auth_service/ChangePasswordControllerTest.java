package dev.yolbert.auth_service;

import dev.yolbert.auth_service.config.JwtTokenProvider;
import dev.yolbert.auth_service.domain.entity.Session;
import dev.yolbert.auth_service.domain.entity.User;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ChangePasswordControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static final String ENDPOINT = "/auth/user/me/password";

    private User createTestUser(String rawPassword) {
        LocalDateTime now = LocalDateTime.now();
        User user = User.builder()
                .id(UUID.randomUUID())
                .fullName("User ChangePass")
                .email("changepass-" + UUID.randomUUID() + "@example.com")
                .passwordHash(passwordEncoder.encode(rawPassword))
                .friendCode("FC" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .createdAt(now)
                .updatedAt(now)
                .verifiedAt(now)
                .build();
        return userRepository.save(user);
    }

    private Session createSession(UUID userId, boolean revoked) throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Session session = Session.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .ipAddress(InetAddress.getLoopbackAddress())
                .refreshTokenHash("hash-" + UUID.randomUUID())
                .revoked(revoked)
                .createdAt(now)
                .updatedAt(now)
                .lastUsedAt(now)
                .expiresAt(now.plusDays(7))
                .build();
        return sessionRepository.save(session);
    }

    private String buildBody(String currentPass, String newPass, String confirmPass) {
        return """
                {
                  "current_password": %s,
                  "new_password": %s,
                  "confirm_new_password": %s
                }
                """.formatted(
                currentPass != null ? "\"" + currentPass + "\"" : "null",
                newPass != null ? "\"" + newPass + "\"" : "null",
                confirmPass != null ? "\"" + confirmPass + "\"" : "null"
        );
    }

    @Test
    void CA01_changePassword_success() throws Exception {
        User user = createTestUser("CurrentPass@123!");
        Session session = createSession(user.getId(), false);
        String token = jwtTokenProvider.generateAccessToken(user.getId(), session.getId());

        String body = buildBody("CurrentPass@123!", "NewPass@456!", "NewPass@456!");

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

        User updatedUser = userRepository.findById(user.getId()).orElseThrow();
        assertThat(passwordEncoder.matches("NewPass@456!", updatedUser.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("CurrentPass@123!", updatedUser.getPasswordHash())).isFalse();
    }

    @Test
    void CA02_changePassword_wrongCurrentPassword() throws Exception {
        User user = createTestUser("CurrentPass@123!");
        Session session = createSession(user.getId(), false);
        String token = jwtTokenProvider.generateAccessToken(user.getId(), session.getId());

        String body = buildBody("WrongPass@123!", "NewPass@456!", "NewPass@456!");

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void CA03_changePassword_weakNewPassword() throws Exception {
        User user = createTestUser("CurrentPass@123!");
        Session session = createSession(user.getId(), false);
        String token = jwtTokenProvider.generateAccessToken(user.getId(), session.getId());

        String body = buildBody("CurrentPass@123!", "nosymbol123", "nosymbol123");

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'newPassword')]").exists());
    }

    @Test
    void CA04_changePassword_mismatchedConfirm() throws Exception {
        User user = createTestUser("CurrentPass@123!");
        Session session = createSession(user.getId(), false);
        String token = jwtTokenProvider.generateAccessToken(user.getId(), session.getId());

        String body = buildBody("CurrentPass@123!", "NewPass@456!", "DifferentPass@456!");

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void CA05_changePassword_samePasswordAsCurrent() throws Exception {
        User user = createTestUser("CurrentPass@123!");
        Session session = createSession(user.getId(), false);
        String token = jwtTokenProvider.generateAccessToken(user.getId(), session.getId());

        String body = buildBody("CurrentPass@123!", "CurrentPass@123!", "CurrentPass@123!");

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Tu nueva contraseña no puede ser la misma que ya utilizas"));
    }

    @Test
    void CA06_changePassword_revokesOtherSessionsOnly() throws Exception {
        User user = createTestUser("CurrentPass@123!");
        Session activeSession1 = createSession(user.getId(), false);
        Session activeSession2 = createSession(user.getId(), false);

        String token = jwtTokenProvider.generateAccessToken(user.getId(), activeSession1.getId());

        String body = buildBody("CurrentPass@123!", "NewPass@456!", "NewPass@456!");

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        Session updatedSession1 = sessionRepository.findById(activeSession1.getId()).orElseThrow();
        Session updatedSession2 = sessionRepository.findById(activeSession2.getId()).orElseThrow();

        assertThat(updatedSession1.isRevoked()).isFalse();
        assertThat(updatedSession2.isRevoked()).isTrue();
    }
}
