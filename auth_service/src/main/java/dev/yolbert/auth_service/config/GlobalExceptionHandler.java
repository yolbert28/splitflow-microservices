package dev.yolbert.auth_service.config;

import dev.yolbert.auth_service.dto.ApiErrorDetail;
import dev.yolbert.auth_service.dto.ApiErrorResponse;
import dev.yolbert.auth_service.utils.exceptions.EmailAlreadyExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles bean validation errors (@Valid on request body).
     * Returns 400 with a list of field-level error details.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex) {
        List<ApiErrorDetail> errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fe -> ApiErrorDetail.builder()
                        .field(fe.getField())
                        .message(fe.getDefaultMessage())
                        .build())
                .toList();

        return ResponseEntity.badRequest().body(
                ApiErrorResponse.builder()
                        .message("La solicitud contiene datos inválidos.")
                        .errors(errors)
                        .build()
        );
    }

    /**
     * Handles duplicate email on registration.
     * Returns 400 with a field-level error detail.
     */
    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ApiErrorResponse> handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
        List<ApiErrorDetail> errors = List.of(
                ApiErrorDetail.builder()
                        .field("email")
                        .message("El email ya está registrado.")
                        .build()
        );

        return ResponseEntity.badRequest().body(
                ApiErrorResponse.builder()
                        .message("La solicitud contiene datos inválidos.")
                        .errors(errors)
                        .build()
        );
    }

    /**
     * Catch-all handler. Logs internally but does not expose details to the client.
     * Returns 500 with a generic message.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiErrorResponse.builder()
                        .message("Ocurrió un error inesperado. Intenta nuevamente más tarde.")
                        .errors(List.of())
                        .build()
        );
    }
}
