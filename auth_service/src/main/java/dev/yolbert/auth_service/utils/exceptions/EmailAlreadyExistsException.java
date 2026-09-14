package dev.yolbert.auth_service.utils.exceptions;

public class EmailAlreadyExistsException extends RuntimeException {

    public EmailAlreadyExistsException(String email) {
        super("El email ya está registrado: " + email);
    }
}
