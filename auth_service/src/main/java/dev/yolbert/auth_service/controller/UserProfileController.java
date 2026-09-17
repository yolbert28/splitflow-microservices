package dev.yolbert.auth_service.controller;

import dev.yolbert.auth_service.dto.*;
import dev.yolbert.auth_service.service.ChangePasswordUseCase;
import dev.yolbert.auth_service.service.DeleteAccountUseCase;
import dev.yolbert.auth_service.service.RegenerateFriendCodeUseCase;
import dev.yolbert.auth_service.service.UpdateProfileUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/auth/user")
public class UserProfileController {

    private final ChangePasswordUseCase changePasswordUseCase;
    private final UpdateProfileUseCase updateProfileUseCase;
    private final RegenerateFriendCodeUseCase regenerateFriendCodeUseCase;
    private final DeleteAccountUseCase deleteAccountUseCase;

    public UserProfileController(ChangePasswordUseCase changePasswordUseCase,
                                 UpdateProfileUseCase updateProfileUseCase,
                                 RegenerateFriendCodeUseCase regenerateFriendCodeUseCase,
                                 DeleteAccountUseCase deleteAccountUseCase) {
        this.changePasswordUseCase       = changePasswordUseCase;
        this.updateProfileUseCase       = updateProfileUseCase;
        this.regenerateFriendCodeUseCase = regenerateFriendCodeUseCase;
        this.deleteAccountUseCase        = deleteAccountUseCase;
    }

    @PatchMapping("/me/password")
    public ResponseEntity<ApiSuccessResponse<Void>> changePassword(@Valid @RequestBody ChangePasswordCommand command) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UUID userId = (UUID) auth.getPrincipal();
        UUID sessionId = (UUID) auth.getDetails();

        changePasswordUseCase.execute(userId, sessionId, command);

        return ResponseEntity.ok(ApiSuccessResponse.<Void>builder()
                .message("Contraseña actualizada exitosamente.")
                .build());
    }

    @PatchMapping("/me")
    public ResponseEntity<ApiSuccessResponse<UserResponseData>> updateProfile(@Valid @RequestBody UpdateProfileCommand command) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UUID userId = (UUID) auth.getPrincipal();

        UserResponseData responseData = updateProfileUseCase.execute(userId, command);

        return ResponseEntity.ok(ApiSuccessResponse.<UserResponseData>builder()
                .message("Perfil actualizado exitosamente.")
                .data(responseData)
                .build());
    }

    @PostMapping("/me/friend-code")
    public ResponseEntity<ApiSuccessResponse<FriendCodeResponseData>> regenerateFriendCode() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UUID userId = (UUID) auth.getPrincipal();

        FriendCodeResponseData responseData = regenerateFriendCodeUseCase.execute(userId);

        return ResponseEntity.ok(ApiSuccessResponse.<FriendCodeResponseData>builder()
                .message("Código de amigo generado exitosamente.")
                .data(responseData)
                .build());
    }

    @DeleteMapping("/me")
    public ResponseEntity<ApiSuccessResponse<Void>> deleteAccount() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UUID userId = (UUID) auth.getPrincipal();

        deleteAccountUseCase.execute(userId);

        return ResponseEntity.ok(ApiSuccessResponse.<Void>builder()
                .message("Cuenta eliminada exitosamente.")
                .build());
    }
}
