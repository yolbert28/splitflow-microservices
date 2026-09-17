package dev.yolbert.auth_service.utils.exceptions;

public class WrongCurrentPasswordException extends RuntimeException {

    public WrongCurrentPasswordException() {
        super("Contraseña o usuario no válido.");
    }
}
