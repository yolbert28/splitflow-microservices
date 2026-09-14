package dev.yolbert.auth_service.utils;

import java.security.SecureRandom;

public final class FriendCodeGenerator {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private FriendCodeGenerator() {}

    /**
     * Generates a 10-character URL-safe alphanumeric friend code [A-Z0-9].
     * Safe to append directly to a URL and encode as a QR code.
     */
    public static String generate() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
