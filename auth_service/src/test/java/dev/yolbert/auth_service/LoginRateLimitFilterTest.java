package dev.yolbert.auth_service;

import dev.yolbert.auth_service.config.LoginRateLimitFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LoginRateLimitFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LoginRateLimitFilter loginRateLimitFilter;

    private static final String LOGIN_ENDPOINT = "/auth/login";

    private static final String BODY = """
            {
              "email": "nobody@example.com",
              "password": "WrongPassword!"
            }
            """;

    @BeforeEach
    void setUp() {
        loginRateLimitFilter.reset();
    }

    @Test
    void rateLimit_exceeded() throws Exception {
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post(LOGIN_ENDPOINT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post(LOGIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Demasiadas peticiones. Intenta de nuevo más tarde."));
    }
}