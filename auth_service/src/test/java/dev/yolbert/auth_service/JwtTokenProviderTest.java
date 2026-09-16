package dev.yolbert.auth_service;

import dev.yolbert.auth_service.config.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        RSAPrivateKey privateKey = (RSAPrivateKey) pair.getPrivate();
        RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();

        String privateKeyPem = Base64.getEncoder().encodeToString(privateKey.getEncoded());
        String publicKeyPem = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        provider = new JwtTokenProvider(privateKeyPem, publicKeyPem);
        provider.init();
    }

    @Test
    void generateAccessToken_isValidAndExposesSubject() {
        UUID userId = UUID.randomUUID();

        String token = provider.generateAccessToken(userId);

        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3);
        assertThat(provider.validateToken(token)).isTrue();
        assertThat(provider.getUserIdFromToken(token)).isEqualTo(userId);
    }

    @Test
    void validateToken_rejectsMangledToken() {
        String token = provider.generateAccessToken(UUID.randomUUID());

        String mangled = token.substring(0, token.length() - 2) + "aa";

        assertThat(provider.validateToken(mangled)).isFalse();
    }

    @Test
    void validateToken_rejectsGarbage() {
        assertThat(provider.validateToken("not-a-jwt")).isFalse();
    }
}