package dev.yolbert.auth_service.utils.exceptions;

public class TooManyOtpAttemptsException extends RuntimeException {
    public TooManyOtpAttemptsException(String message) {
        super(message);
    }
}
