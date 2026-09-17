package dev.yolbert.auth_service.config;

import dev.yolbert.auth_service.dto.ApiErrorDetail;
import dev.yolbert.auth_service.dto.ApiErrorResponse;
import dev.yolbert.auth_service.utils.exceptions.EmailAlreadyExistsException;
import dev.yolbert.auth_service.utils.exceptions.InvalidCredentialsException;
import dev.yolbert.auth_service.utils.exceptions.InvalidOtpException;
import dev.yolbert.auth_service.utils.exceptions.SessionNotFoundException;
import dev.yolbert.auth_service.utils.exceptions.TooManyOtpAttemptsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    @ExceptionHandler(InvalidOtpException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidOtp(InvalidOtpException ex) {
        List<ApiErrorDetail> errors = List.of(
                ApiErrorDetail.builder()
                        .field("otp_code")
                        .code("INVALID_OR_EXPIRED")
                        .message(ex.getMessage())
                        .build()
        );

        return ResponseEntity.badRequest().body(
                ApiErrorResponse.builder()
                        .message(ex.getMessage())
                        .errors(errors)
                        .build()
        );
    }

    @ExceptionHandler(TooManyOtpAttemptsException.class)
    public ResponseEntity<ApiErrorResponse> handleTooManyOtpAttempts(TooManyOtpAttemptsException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(
                ApiErrorResponse.builder()
                        .message(ex.getMessage())
                        .build()
        );
    }

    /**
     * Handles invalid login credentials. The response is generic so it does not
     * reveal whether the email exists.
     * Returns 401.
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ApiErrorResponse.builder()
                        .message(ex.getMessage())
                        .build()
        );
    }

    /**
     * Handles a refresh token that does not match any active session.
     * Returns 401.
     */
    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleSessionNotFound(SessionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ApiErrorResponse.builder()
                        .message(ex.getMessage())
                        .build()
        );
    }

    @ExceptionHandler(dev.yolbert.auth_service.utils.exceptions.WrongCurrentPasswordException.class)
    public ResponseEntity<ApiErrorResponse> handleWrongCurrentPassword(dev.yolbert.auth_service.utils.exceptions.WrongCurrentPasswordException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ApiErrorResponse.builder()
                        .message(ex.getMessage())
                        .build()
        );
    }

    @ExceptionHandler(dev.yolbert.auth_service.utils.exceptions.SamePasswordException.class)
    public ResponseEntity<ApiErrorResponse> handleSamePassword(dev.yolbert.auth_service.utils.exceptions.SamePasswordException ex) {
        return ResponseEntity.badRequest().body(
                ApiErrorResponse.builder()
                        .message(ex.getMessage())
                        .build()
        );
    }

    @ExceptionHandler(dev.yolbert.auth_service.utils.exceptions.EmailConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleEmailConflict(dev.yolbert.auth_service.utils.exceptions.EmailConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiErrorResponse.builder()
                        .message(ex.getMessage())
                        .build()
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(
                ApiErrorResponse.builder()
                        .message(ex.getMessage())
                        .build()
        );
    }

    /**
     * Handles requests to paths without a handler (e.g., the removed
     * {@code POST /user/} registration endpoint).
     * Returns 404.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiErrorResponse.builder()
                        .message("Recurso no encontrado.")
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
                        .build()
        );
    }
}
