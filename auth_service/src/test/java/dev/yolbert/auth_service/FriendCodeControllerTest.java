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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FriendCodeControllerTest {

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

    private static final String ENDPOINT = "/auth/user/me/friend-code";

    private User createTestUser() {
        LocalDateTime now = LocalDateTime.now();
        User user = User.builder()
                .id(UUID.randomUUID())
                .fullName("Friend Code User")
                .email("fc-" + UUID.randomUUID() + "@example.com")
                .passwordHash(passwordEncoder.encode("Pass@12345!"))
                .friendCode("OLDCODE123")
                .createdAt(now)
                .updatedAt(now)
                .verifiedAt(now)
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
    void CA16_regenerateFriendCode_success() throws Exception {
        User user = createTestUser();
        Session session = createSession(user.getId());
        String token = jwtTokenProvider.generateAccessToken(user.getId(), session.getId());

        mockMvc.perform(post(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.friend_code").isNotEmpty());

        User updatedUser = userRepository.findById(user.getId()).orElseThrow();
        assertThat(updatedUser.getFriendCode()).isNotEqualTo("OLDCODE123");
        assertThat(updatedUser.getFriendCode()).matches("[A-Z0-9]{10}");
    }
}
