package dev.yolbert.auth_service.controller;

import dev.yolbert.auth_service.dto.ApiSuccessResponse;
import dev.yolbert.auth_service.dto.VerifyEmailCommand;
import dev.yolbert.auth_service.dto.VerifyEmailResponseData;
import dev.yolbert.auth_service.service.VerifyEmailUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final VerifyEmailUseCase verifyEmailUseCase;

    public AuthController(VerifyEmailUseCase verifyEmailUseCase) {
        this.verifyEmailUseCase = verifyEmailUseCase;
    }

    /**
     * POST /auth/verify-email
     * Verifies user email with OTP code.
     */
    @PostMapping("/verify-email")
    public ResponseEntity<ApiSuccessResponse<VerifyEmailResponseData>> verifyEmail(
            @Valid @RequestBody VerifyEmailCommand command) {

        VerifyEmailResponseData data = verifyEmailUseCase.execute(command);

        return ResponseEntity.ok(
                ApiSuccessResponse.<VerifyEmailResponseData>builder()
                        .message("Correo verificado exitosamente.")
                        .data(data)
                        .build()
        );
    }
}
