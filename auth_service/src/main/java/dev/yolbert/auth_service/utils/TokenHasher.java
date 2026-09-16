package dev.yolbert.auth_service.utils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deterministic SHA-256 hash for opaque tokens (e.g. refresh tokens).
 *
 * <p>Unlike BCrypt, SHA-256 produces the same output for the same input, which
 * allows a single indexed {@code WHERE refresh_token_hash = ?} query instead of
 * a full-table scan with per-row BCrypt comparison.
 *
 * <p>SHA-256 without salt is secure here because the token itself already
 * carries 128 bits of cryptographic randomness (UUID v4). Rainbow-table
 * attacks against a 2^128 search space are computationally infeasible.
 */
public final class TokenHasher {

    private TokenHasher() {
        // utility class — not instantiable
    }

    /**
     * Returns the lowercase hex-encoded SHA-256 digest of {@code rawToken}.
     *
     * @param rawToken the plaintext token to hash; must not be {@code null}
     * @return 64-character lowercase hex string
     * @throws IllegalStateException if SHA-256 is unavailable (never on any
     *                               standard JVM)
     */
    public static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
