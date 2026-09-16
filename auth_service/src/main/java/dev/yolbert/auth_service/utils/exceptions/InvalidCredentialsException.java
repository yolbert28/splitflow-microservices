package dev.yolbert.auth_service.utils.exceptions;

/**
 * Thrown when login credentials are rejected (unknown email, wrong password or
 * unverified account). The message is identical in every case to avoid leaking
 * whether the email exists.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String message) {
        super(message);
    }
}