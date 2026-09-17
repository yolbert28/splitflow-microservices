package dev.yolbert.auth_service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.yolbert.auth_service.domain.entity.Otp;
import dev.yolbert.auth_service.domain.entity.OtpPurpose;
import dev.yolbert.auth_service.domain.entity.Outbox;
import dev.yolbert.auth_service.domain.entity.User;
import dev.yolbert.auth_service.repository.OtpRepository;
import dev.yolbert.auth_service.repository.OutboxRepository;
import dev.yolbert.auth_service.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RegisterControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OtpRepository otpRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private UserRepository userRepository;

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private static final String VALID_FULL_NAME = "Ana María Torres";
    private static final String VALID_EMAIL     = "ana.torres@example.com";
    private static final String VALID_PASSWORD  = "Secure@123!";
    private static final String ENDPOINT        = "/auth/register";

    private String buildBody(String fullName, String email, String password, String confirmPassword) {
        return """
                {
                  "full_name": %s,
                  "email": %s,
                  "password": %s,
                  "confirm_password": %s
                }
                """.formatted(
                fullName        != null ? "\"" + fullName + "\""        : "null",
                email           != null ? "\"" + email + "\""           : "null",
                password        != null ? "\"" + password + "\""        : "null",
                confirmPassword != null ? "\"" + confirmPassword + "\"" : "null"
        );
    }

    private User createDeletedUser(String email) {
        LocalDateTime now = LocalDateTime.now();
        User user = User.builder()
                .id(UUID.randomUUID())
                .fullName("Cuenta Anterior")
                .email(email)
                .passwordHash("hash-deleted")
                .friendCode("ANTERIOR00")
                .createdAt(now.minusDays(30))
                .updatedAt(now.minusDays(1))
                .deletedAt(now)
                .build();
        return userRepository.save(user);
    }

    // ─── Criterio 1 (CA-09): happy path ─────────────────────────────────────────

    /**
     * CA-09 / Criterio 1: Dados datos correctos → 201 con el body esperado.
     */
    @Test
    void registerUser_success() throws Exception {
        String body = buildBody(VALID_FULL_NAME, VALID_EMAIL, VALID_PASSWORD, VALID_PASSWORD);

        MvcResult result = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.email").value(VALID_EMAIL))
                .andExpect(jsonPath("$.data.full_name").value(VALID_FULL_NAME))
                .andExpect(jsonPath("$.data.verified").value(false))
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andExpect(jsonPath("$.data.friend_code").isNotEmpty())
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data");
        assertThat(data.path("friend_code").asText()).matches("[A-Z0-9]{10}");

        UUID userId = UUID.fromString(data.path("id").asText());

        List<Otp> otps = otpRepository.findAll();
        assertThat(otps).isNotEmpty();
        Otp userOtp = otps.stream()
                .filter(o -> o.getUserId().equals(userId))
                .findFirst()
                .orElseThrow();
        assertThat(userOtp.getPurpose()).isEqualTo(OtpPurpose.EMAIL_VERIFICATION);
        assertThat(userOtp.getCode()).matches("[0-9]{6}");

        List<Outbox> outboxEvents = outboxRepository.findAll();
        assertThat(outboxEvents).isNotEmpty();
        Outbox event = outboxEvents.stream()
                .filter(o -> userId.equals(o.getAggregateId()))
                .findFirst()
                .orElseThrow();

        JsonNode payload = objectMapper.readTree(event.getPayload());
        assertThat(payload.path("otp_code").asText()).isEqualTo(userOtp.getCode());
        assertThat(payload.path("id").asText()).isEqualTo(userId.toString());
        assertThat(payload.path("email").asText()).isEqualTo(VALID_EMAIL);
        assertThat(payload.path("full_name").asText()).isEqualTo(VALID_FULL_NAME);
    }

    // ─── Criterio 2: datos inválidos — todos los campos a la vez ────────────────

    /**
     * Criterio 2 — todos inválidos: email sin @, nombre con letras sueltas, contraseña débil → 400 con errors[].
     */
    @Test
    void registerUser_allFieldsInvalid() throws Exception {
        String body = buildBody("A B C", "not-an-email", "weak", "weak");

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    // ─── Criterio 2: campo ausente — full_name ───────────────────────────────────

    /**
     * Criterio 2 — sin full_name → 400 con error en el campo fullName.
     */
    @Test
    void registerUser_missingFullName() throws Exception {
        String body = buildBody(null, VALID_EMAIL, VALID_PASSWORD, VALID_PASSWORD);

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.errors[?(@.field == 'fullName')]").exists());
    }

    // ─── Criterio 2: campo ausente — email ──────────────────────────────────────

    /**
     * Criterio 2 — sin email → 400 con error en el campo email.
     */
    @Test
    void registerUser_missingEmail() throws Exception {
        String body = buildBody(VALID_FULL_NAME, null, VALID_PASSWORD, VALID_PASSWORD);

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.errors[?(@.field == 'email')]").exists());
    }

    // ─── Criterio 2: campo ausente — password ───────────────────────────────────

    /**
     * Criterio 2 — sin password → 400 con error en el campo password.
     */
    @Test
    void registerUser_missingPassword() throws Exception {
        String body = buildBody(VALID_FULL_NAME, VALID_EMAIL, null, VALID_PASSWORD);

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.errors[?(@.field == 'password')]").exists());
    }

    // ─── Criterio 2: campo ausente — confirm_password ───────────────────────────

    /**
     * Criterio 2 — sin confirm_password → 400 con error en el campo confirmPassword.
     */
    @Test
    void registerUser_missingConfirmPassword() throws Exception {
        String body = buildBody(VALID_FULL_NAME, VALID_EMAIL, VALID_PASSWORD, null);

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.errors[?(@.field == 'confirmPassword')]").exists());
    }

    // ─── Criterio 2: passwords no coinciden ─────────────────────────────────────

    /**
     * Criterio 2 — password != confirm_password → 400 con error en confirm_password.
     */
    @Test
    void registerUser_passwordMismatch() throws Exception {
        String body = buildBody(VALID_FULL_NAME, VALID_EMAIL, VALID_PASSWORD, "Differ@nt9!");

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.errors[?(@.field == 'confirm_password')]").exists());
    }

    // ─── Criterio 2: email duplicado ────────────────────────────────────────────

    /**
     * Criterio 2 — email ya registrado → 400 con error en el campo email.
     */
    @Test
    void registerUser_duplicateEmail() throws Exception {
        String uniqueEmail = "duplicate@example.com";
        String body = buildBody(VALID_FULL_NAME, uniqueEmail, VALID_PASSWORD, VALID_PASSWORD);

        // Primera vez: debe funcionar
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        // Segunda vez: email duplicado
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.errors[?(@.field == 'email')]").exists());
    }

    // ─── Criterio 2: contraseña débil ───────────────────────────────────────────

    /**
     * Criterio 2 — contraseña sin símbolo → 400 con error en password.
     */
    @Test
    void registerUser_weakPassword_noSymbol() throws Exception {
        String body = buildBody(VALID_FULL_NAME, "weak@example.com", "NoSymbol123", "NoSymbol123");

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.errors[?(@.field == 'password')]").exists());
    }

    // ─── Criterio 2: nombre con espacios estratégicos ───────────────────────────

    /**
     * Criterio 2 — nombre tipo "A L B E R T O" → 400 con error en full_name.
     */
    @Test
    void registerUser_spacedLetterName() throws Exception {
        String body = buildBody("A L B E R T O", "spaced@example.com", VALID_PASSWORD, VALID_PASSWORD);

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.errors[?(@.field == 'fullName')]").exists());
    }

    // ─── Criterio 2: email con formato inválido ──────────────────────────────────

    /**
     * Criterio 2 — email sin @ → 400 con error en email.
     */
    @Test
    void registerUser_invalidEmailFormat() throws Exception {
        String body = buildBody(VALID_FULL_NAME, "notanemail.com", VALID_PASSWORD, VALID_PASSWORD);

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.errors[?(@.field == 'email')]").exists());
    }

    // ─── CA-04: registro con email de cuenta eliminada ──────────────────────────

    /**
     * CA-04 — el email de una cuenta eliminada puede reutilizarse; la nueva
     * cuenta empieza desde cero (id y friend_code distintos).
     */
    @Test
    void registerUser_withEmailOfDeletedAccount_createsNewAccount() throws Exception {
        String email = "reuse-" + UUID.randomUUID() + "@example.com";
        User deleted = createDeletedUser(email);

        String body = buildBody("Nueva Persona", email, VALID_PASSWORD, VALID_PASSWORD);

        MvcResult result = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.friend_code").isNotEmpty())
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data");
        UUID newUserId = UUID.fromString(data.path("id").asText());
        assertThat(newUserId).isNotEqualTo(deleted.getId());
        assertThat(data.path("friend_code").asText()).isNotEqualTo(deleted.getFriendCode());

        assertThat(userRepository.findById(deleted.getId())).isPresent();
        assertThat(userRepository.findById(newUserId)).isPresent();
        assertThat(userRepository.findById(newUserId).orElseThrow().getDeletedAt()).isNull();
    }

    // ─── CA-10: POST /user/ ya no existe ────────────────────────────────────────

    /**
     * CA-10 — tras la migración, el endpoint antiguo devuelve 404.
     */
    @Test
    void registerUser_legacyEndpoint_returns404() throws Exception {
        String body = buildBody(VALID_FULL_NAME, VALID_EMAIL, VALID_PASSWORD, VALID_PASSWORD);

        mockMvc.perform(post("/user/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    // ─── CA-11: photo_url válida en el registro ─────────────────────────────────

    /**
     * CA-11 — con photo_url válida se persiste y aparece en la respuesta.
     */
    @Test
    void registerUser_withValidPhotoUrl_persistsIt() throws Exception {
        String email = "photo-" + UUID.randomUUID() + "@example.com";
        String validUrl = "https://cdn.example.com/avatar.png";
        String body = """
                {
                  "full_name": "%s",
                  "email": "%s",
                  "password": "%s",
                  "confirm_password": "%s",
                  "photo_url": "%s"
                }
                """.formatted(VALID_FULL_NAME, email, VALID_PASSWORD, VALID_PASSWORD, validUrl);

        MvcResult result = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.photo_url").value(validUrl))
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data");
        User saved = userRepository.findById(UUID.fromString(data.path("id").asText())).orElseThrow();
        assertThat(saved.getPhotoUrl()).isEqualTo(validUrl);
    }

    // ─── CA-12: registro sin photo_url ──────────────────────────────────────────

    /**
     * CA-12 — sin photo_url en el payload, la respuesta incluye "photo_url": null.
     */
    @Test
    void registerUser_withoutPhotoUrl_photoUrlIsNull() throws Exception {
        String email = "nophoto-" + UUID.randomUUID() + "@example.com";
        String body = buildBody(VALID_FULL_NAME, email, VALID_PASSWORD, VALID_PASSWORD);

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.photo_url").value((Object) null));
    }

    // ─── CA-13: photo_url inválida en el registro ───────────────────────────────

    /**
     * CA-13 — photo_url con esquema http → 400 con detalle del campo photoUrl.
     */
    @Test
    void registerUser_invalidPhotoUrl_badRequest() throws Exception {
        String email = "badphoto-" + UUID.randomUUID() + "@example.com";
        String body = """
                {
                  "full_name": "%s",
                  "email": "%s",
                  "password": "%s",
                  "confirm_password": "%s",
                  "photo_url": "http://cdn.example.com/avatar.png"
                }
                """.formatted(VALID_FULL_NAME, email, VALID_PASSWORD, VALID_PASSWORD);

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'photoUrl')]").exists());
    }
}