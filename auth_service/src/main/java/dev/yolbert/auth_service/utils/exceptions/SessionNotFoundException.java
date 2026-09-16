package dev.yolbert.auth_service.utils.exceptions;

/**
 * Thrown when a refresh token does not match any active session.
 */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException(String message) {
        super(message);
    }
}