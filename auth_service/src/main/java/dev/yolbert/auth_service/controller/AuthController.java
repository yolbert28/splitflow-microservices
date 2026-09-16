package dev.yolbert.auth_service.controller;

import dev.yolbert.auth_service.dto.ApiSuccessResponse;
import dev.yolbert.auth_service.dto.AuthTokenResponseData;
import dev.yolbert.auth_service.dto.LoginCommand;
import dev.yolbert.auth_service.dto.LogoutCommand;
import dev.yolbert.auth_service.dto.RefreshTokenCommand;
import dev.yolbert.auth_service.dto.VerifyEmailCommand;
import dev.yolbert.auth_service.dto.VerifyEmailResponseData;
import dev.yolbert.auth_service.dto.VerifyLoginOtpCommand;
import dev.yolbert.auth_service.service.LoginUseCase;
import dev.yolbert.auth_service.service.LogoutUseCase;
import dev.yolbert.auth_service.service.RefreshTokenUseCase;
import dev.yolbert.auth_service.service.VerifyEmailUseCase;
import dev.yolbert.auth_service.service.VerifyLoginOtpUseCase;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final VerifyEmailUseCase verifyEmailUseCase;
    private final LoginUseCase loginUseCase;
    private final VerifyLoginOtpUseCase verifyLoginOtpUseCase;
    private final RefreshTokenUseCase refreshTokenUseCase;
    private final LogoutUseCase logoutUseCase;

    public AuthController(VerifyEmailUseCase verifyEmailUseCase,
                          LoginUseCase loginUseCase,
                          VerifyLoginOtpUseCase verifyLoginOtpUseCase,
                          RefreshTokenUseCase refreshTokenUseCase,
                          LogoutUseCase logoutUseCase) {
        this.verifyEmailUseCase    = verifyEmailUseCase;
        this.loginUseCase          = loginUseCase;
        this.verifyLoginOtpUseCase = verifyLoginOtpUseCase;
        this.refreshTokenUseCase   = refreshTokenUseCase;
        this.logoutUseCase         = logoutUseCase;
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

    /**
     * POST /auth/login
     * Validates credentials and sends a LOGIN_2FA OTP to the user's email.
     */
    @PostMapping("/login")
    public ResponseEntity<ApiSuccessResponse<Void>> login(@Valid @RequestBody LoginCommand command) {
        loginUseCase.execute(command);

        return ResponseEntity.ok(
                ApiSuccessResponse.<Void>builder()
                        .message("Se ha enviado un código de verificación a tu correo.")
                        .build()
        );
    }

    /**
     * POST /auth/login/verify-2fa
     * Verifies the 2FA OTP and issues the access/refresh token pair.
     */
    @PostMapping("/login/verify-2fa")
    public ResponseEntity<ApiSuccessResponse<AuthTokenResponseData>> verifyLoginOtp(
            @Valid @RequestBody VerifyLoginOtpCommand command,
            HttpServletRequest request) {

        AuthTokenResponseData data = verifyLoginOtpUseCase.execute(command, request);

        return ResponseEntity.ok(
                ApiSuccessResponse.<AuthTokenResponseData>builder()
                        .message("Autenticación completada.")
                        .data(data)
                        .build()
        );
    }

    /**
     * POST /auth/refresh
     * Rotates the refresh token and issues a new access token.
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiSuccessResponse<AuthTokenResponseData>> refresh(
            @Valid @RequestBody RefreshTokenCommand command,
            HttpServletRequest request) {

        AuthTokenResponseData data = refreshTokenUseCase.execute(command, request);

        return ResponseEntity.ok(
                ApiSuccessResponse.<AuthTokenResponseData>builder()
                        .message("Sesión renovada.")
                        .data(data)
                        .build()
        );
    }

    /**
     * POST /auth/logout
     * Revokes the session associated with the received refresh token.
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiSuccessResponse<Void>> logout(@Valid @RequestBody LogoutCommand command) {
        logoutUseCase.execute(command);

        return ResponseEntity.ok(
                ApiSuccessResponse.<Void>builder()
                        .message("Sesión cerrada exitosamente.")
                        .build()
        );
    }

    /**
     * GET /auth/ping — protected probe endpoint used to exercise JWT protection.
     */
    @GetMapping("/ping")
    public ResponseEntity<ApiSuccessResponse<Void>> ping() {
        return ResponseEntity.ok(
                ApiSuccessResponse.<Void>builder()
                        .message("pong")
                        .build()
        );
    }
}