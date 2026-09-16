package dev.yolbert.auth_service;

import dev.yolbert.auth_service.config.JwtTokenProvider;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class JwtProtectedEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Value("${jwt.private-key}")
    private String privateKeyPem;

    private static final String PING_ENDPOINT = "/auth/ping";

    @Test
    void accessWithValidToken() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(java.util.UUID.randomUUID());

        mockMvc.perform(get(PING_ENDPOINT).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void accessWithExpiredToken() throws Exception {
        String token = buildExpiredToken();

        mockMvc.perform(get(PING_ENDPOINT).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void accessWithMangledToken() throws Exception {
        String valid = jwtTokenProvider.generateAccessToken(java.util.UUID.randomUUID());
        String mangled = valid.substring(0, valid.length() - 2) + "aa";

        mockMvc.perform(get(PING_ENDPOINT).header("Authorization", "Bearer " + mangled))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void accessWithoutToken() throws Exception {
        mockMvc.perform(get(PING_ENDPOINT))
                .andExpect(status().isUnauthorized());
    }

    private String buildExpiredToken() throws Exception {
        byte[] der = Base64.getDecoder().decode(privateKeyPem.replaceAll("\\s", ""));
        PrivateKey privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(der));
        return Jwts.builder()
                .subject("00000000-0000-0000-0000-000000000000")
                .issuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .expiration(Date.from(Instant.now().minusSeconds(3600)))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }
}