package dev.yolbert.auth_service;

import dev.yolbert.auth_service.config.JwtAuthenticationFilter;
import dev.yolbert.auth_service.config.JwtTokenProvider;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class JwtAuthenticationFilterTest {

    private JwtAuthenticationFilter filter;
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

        filter = new JwtAuthenticationFilter(provider);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilter_withValidToken_populatesSecurityContext() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = provider.generateAccessToken(userId);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/ping");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal()).isEqualTo(userId);
    }

    @Test
    void doFilter_withoutToken_leavesContextEmpty() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/ping");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void doFilter_withInvalidToken_clearsContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/ping");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer invalid.token.value");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}