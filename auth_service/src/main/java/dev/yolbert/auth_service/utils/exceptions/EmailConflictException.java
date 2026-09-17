package dev.yolbert.auth_service.utils.exceptions;

public class EmailConflictException extends RuntimeException {

    public EmailConflictException(String email) {
        super("El email " + email + " ya está registrado por otro usuario.");
    }
}
