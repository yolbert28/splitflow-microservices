package dev.yolbert.auth_service.utils.exceptions;

public class SamePasswordException extends RuntimeException {

    public SamePasswordException() {
        super("Tu nueva contraseña no puede ser la misma que ya utilizas");
    }

    public SamePasswordException(String message) {
        super(message);
    }
}
