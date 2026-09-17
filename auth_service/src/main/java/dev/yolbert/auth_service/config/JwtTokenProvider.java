package dev.yolbert.auth_service.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Generates and validates signed RS256 access tokens (JWT).
 *
 * <p>Keys are read from configuration as base64 PEM without BEGIN/END headers,
 * so the private key never lives in source code in lower environments.
 */
@Component
public class JwtTokenProvider {

    public static final long ACCESS_TOKEN_TTL_SECONDS   = 3600;
    public static final long REFRESH_TOKEN_TTL_SECONDS  = 604800;

    private final String privateKeyPem;
    private final String publicKeyPem;

    private PrivateKey privateKey;
    private PublicKey publicKey;

    public JwtTokenProvider(@Value("${jwt.private-key}") String privateKeyPem,
                            @Value("${jwt.public-key}") String publicKeyPem) {
        this.privateKeyPem = privateKeyPem;
        this.publicKeyPem  = publicKeyPem;
    }

    @PostConstruct
    public void init() {
        this.privateKey = parsePrivateKey(privateKeyPem);
        this.publicKey  = parsePublicKey(publicKeyPem);
    }

    /** Creates a signed access token with claims {@code sub} (user UUID), {@code sid} (session UUID), and {@code exp}. */
    public String generateAccessToken(UUID userId, UUID sessionId) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(userId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ACCESS_TOKEN_TTL_SECONDS)));
        if (sessionId != null) {
            builder.claim("sid", sessionId.toString());
        }
        return builder.signWith(privateKey, Jwts.SIG.RS256).compact();
    }

    /** Overload for creating access token without sessionId. */
    public String generateAccessToken(UUID userId) {
        return generateAccessToken(userId, null);
    }

    /** Returns true only if the token is well-formed and its signature verifies. */
    public boolean validateToken(String token) {
        try {
            getClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Extracts the {@code sub} claim from a verified token. */
    public UUID getUserIdFromToken(String token) {
        return UUID.fromString(getClaims(token).getSubject());
    }

    /** Extracts the {@code sid} claim from a verified token, if present. */
    public UUID getSessionIdFromToken(String token) {
        String sid = getClaims(token).get("sid", String.class);
        return sid != null ? UUID.fromString(sid) : null;
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private static PrivateKey parsePrivateKey(String pem) {
        try {
            byte[] der = decodeKey(pem);
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo cargar la clave privada JWT", e);
        }
    }

    private static PublicKey parsePublicKey(String pem) {
        try {
            byte[] der = decodeKey(pem);
            return KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo cargar la clave pública JWT", e);
        }
    }

    private static byte[] decodeKey(String pem) {
        String cleaned = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(cleaned);
    }
}