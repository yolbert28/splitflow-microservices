package dev.yolbert.auth_service;

import dev.yolbert.auth_service.config.JwtTokenProvider;
import dev.yolbert.auth_service.domain.entity.Otp;
import dev.yolbert.auth_service.domain.entity.OtpPurpose;
import dev.yolbert.auth_service.domain.entity.Session;
import dev.yolbert.auth_service.domain.entity.User;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UpdateProfileControllerTest {

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

    private static final String ENDPOINT = "/auth/user/me";

    private User createTestUser(String fullName, String email) {
        LocalDateTime now = LocalDateTime.now();
        User user = User.builder()
                .id(UUID.randomUUID())
                .fullName(fullName)
                .email(email)
                .passwordHash(passwordEncoder.encode("Pass@12345!"))
                .friendCode("FC" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
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
    void CA11_updateFullName_success() throws Exception {
        User user = createTestUser("Carlos Gomez", "carlos-" + UUID.randomUUID() + "@example.com");
        Session session = createSession(user.getId());
        String token = jwtTokenProvider.generateAccessToken(user.getId(), session.getId());

        String body = """
                {"full_name": "Carlos Eduardo Gomez"}
                """;

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.full_name").value("Carlos Eduardo Gomez"));

        User updatedUser = userRepository.findById(user.getId()).orElseThrow();
        assertThat(updatedUser.getFullName()).isEqualTo("Carlos Eduardo Gomez");
    }

    @Test
    void CA12_updateFullName_invalidFormat() throws Exception {
        User user = createTestUser("Carlos Gomez", "carlos2-" + UUID.randomUUID() + "@example.com");
        Session session = createSession(user.getId());
        String token = jwtTokenProvider.generateAccessToken(user.getId(), session.getId());

        String body = """
                {"full_name": "Carlos123"}
                """;

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'fullName')]").exists());
    }

    @Test
    void CA13_updateEmail_success_requiresReverification() throws Exception {
        User user = createTestUser("Laura Perez", "laura-" + UUID.randomUUID() + "@example.com");
        Session session = createSession(user.getId());
        String token = jwtTokenProvider.generateAccessToken(user.getId(), session.getId());

        String newEmail = "laura.new-" + UUID.randomUUID() + "@example.com";
        String body = """
                {"email": "%s"}
                """.formatted(newEmail);

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(newEmail))
                .andExpect(jsonPath("$.data.verified").value(false));

        User updatedUser = userRepository.findById(user.getId()).orElseThrow();
        assertThat(updatedUser.getEmail()).isEqualTo(newEmail);
        assertThat(updatedUser.getVerifiedAt()).isNull();

        List<Otp> otps = otpRepository.findAll();
        assertThat(otps.stream().anyMatch(o -> o.getUserId().equals(user.getId()) && o.getPurpose() == OtpPurpose.EMAIL_VERIFICATION)).isTrue();
    }

    @Test
    void CA15_updateEmail_alreadyTakenByOtherUser_conflict409() throws Exception {
        String existingEmail = "existing-" + UUID.randomUUID() + "@example.com";
        createTestUser("Other User", existingEmail);

        User user = createTestUser("User Updating", "userup-" + UUID.randomUUID() + "@example.com");
        Session session = createSession(user.getId());
        String token = jwtTokenProvider.generateAccessToken(user.getId(), session.getId());

        String body = """
                {"email": "%s"}
                """.formatted(existingEmail);

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void CA20_updatePhotoUrl_validHttps() throws Exception {
        User user = createTestUser("Photo User", "photo-" + UUID.randomUUID() + "@example.com");
        Session session = createSession(user.getId());
        String token = jwtTokenProvider.generateAccessToken(user.getId(), session.getId());

        String validUrl = "https://example.com/profiles/avatar.png";
        String body = """
                {"photo_url": "%s"}
                """.formatted(validUrl);

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.photo_url").value(validUrl));

        User updatedUser = userRepository.findById(user.getId()).orElseThrow();
        assertThat(updatedUser.getPhotoUrl()).isEqualTo(validUrl);
    }

    @Test
    void CA21_updatePhotoUrl_null_removesPhoto() throws Exception {
        User user = createTestUser("Photo User 2", "photo2-" + UUID.randomUUID() + "@example.com");
        user.setPhotoUrl("https://example.com/avatar.jpg");
        userRepository.save(user);

        Session session = createSession(user.getId());
        String token = jwtTokenProvider.generateAccessToken(user.getId(), session.getId());

        String body = """
                {"photo_url": null}
                """;

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.photo_url").value((Object) null));

        User updatedUser = userRepository.findById(user.getId()).orElseThrow();
        assertThat(updatedUser.getPhotoUrl()).isNull();
    }

    @Test
    void CA22_updatePhotoUrl_httpScheme_invalid() throws Exception {
        User user = createTestUser("Photo User 3", "photo3-" + UUID.randomUUID() + "@example.com");
        Session session = createSession(user.getId());
        String token = jwtTokenProvider.generateAccessToken(user.getId(), session.getId());

        String body = """
                {"photo_url": "http://example.com/avatar.jpg"}
                """;

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'photoUrl')]").exists());
    }
}
