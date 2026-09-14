package dev.yolbert.auth_service.utils;

import java.security.SecureRandom;

public final class OtpCodeGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private OtpCodeGenerator() {
    }

    /** Generates a 6-digit numeric OTP code. */
    public static String generate() {
        int number = RANDOM.nextInt(1_000_000);
        return String.format("%06d", number);
    }
}
