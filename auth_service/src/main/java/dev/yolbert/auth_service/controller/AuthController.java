package dev.yolbert.auth_service.controller;

import dev.yolbert.auth_service.dto.*;
import dev.yolbert.auth_service.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
    private final ResetPasswordRequestUseCase resetPasswordRequestUseCase;
    private final ResetPasswordConfirmUseCase resetPasswordConfirmUseCase;
    private final ResendVerificationUseCase resendVerificationUseCase;
    private final RegisterUseCase registerUseCase;

    public AuthController(VerifyEmailUseCase verifyEmailUseCase,
                          LoginUseCase loginUseCase,
                          VerifyLoginOtpUseCase verifyLoginOtpUseCase,
                          RefreshTokenUseCase refreshTokenUseCase,
                          LogoutUseCase logoutUseCase,
                          ResetPasswordRequestUseCase resetPasswordRequestUseCase,
                          ResetPasswordConfirmUseCase resetPasswordConfirmUseCase,
                          ResendVerificationUseCase resendVerificationUseCase,
                          RegisterUseCase registerUseCase) {
        this.verifyEmailUseCase          = verifyEmailUseCase;
        this.loginUseCase                = loginUseCase;
        this.verifyLoginOtpUseCase       = verifyLoginOtpUseCase;
        this.refreshTokenUseCase         = refreshTokenUseCase;
        this.logoutUseCase               = logoutUseCase;
        this.resetPasswordRequestUseCase = resetPasswordRequestUseCase;
        this.resetPasswordConfirmUseCase = resetPasswordConfirmUseCase;
        this.resendVerificationUseCase   = resendVerificationUseCase;
        this.registerUseCase             = registerUseCase;
    }

    /**
     * POST /auth/register
     * Registers a new user account.
     */
    @PostMapping("/register")
    public ResponseEntity<ApiSuccessResponse<UserResponseData>> register(
            @Valid @RequestBody RegisterUserCommand command) {

        UserResponseData data = registerUseCase.execute(command);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiSuccessResponse.<UserResponseData>builder()
                        .message("Cuenta creada. Revisa tu correo para verificar tu cuenta.")
                        .data(data)
                        .build());
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
     * POST /auth/password-reset/request
     * Requests password reset OTP by email. Always responds 200 OK.
     */
    @PostMapping("/password-reset/request")
    public ResponseEntity<ApiSuccessResponse<Void>> requestPasswordReset(
            @Valid @RequestBody PasswordResetRequestCommand command) {
        resetPasswordRequestUseCase.execute(command);

        return ResponseEntity.ok(
                ApiSuccessResponse.<Void>builder()
                        .message("Si el correo está registrado, recibirás un código para restablecer tu contraseña.")
                        .build()
        );
    }

    /**
     * POST /auth/password-reset/confirm
     * Confirms password reset with OTP code and updates password.
     */
    @PostMapping("/password-reset/confirm")
    public ResponseEntity<ApiSuccessResponse<Void>> confirmPasswordReset(
            @Valid @RequestBody PasswordResetConfirmCommand command) {
        resetPasswordConfirmUseCase.execute(command);

        return ResponseEntity.ok(
                ApiSuccessResponse.<Void>builder()
                        .message("Contraseña restablecida exitosamente.")
                        .build()
        );
    }

    /**
     * POST /auth/resend-verification
     * Resends email verification OTP for unverified accounts. Always responds 200 OK.
     */
    @PostMapping("/resend-verification")
    public ResponseEntity<ApiSuccessResponse<Void>> resendVerification(
            @Valid @RequestBody ResendVerificationCommand command) {
        resendVerificationUseCase.execute(command);

        return ResponseEntity.ok(
                ApiSuccessResponse.<Void>builder()
                        .message("Si el correo no ha sido verificado, recibirás un nuevo código.")
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