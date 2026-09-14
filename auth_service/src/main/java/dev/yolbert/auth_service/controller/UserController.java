package dev.yolbert.auth_service.controller;

import dev.yolbert.auth_service.dto.ApiSuccessResponse;
import dev.yolbert.auth_service.dto.RegisterUserCommand;
import dev.yolbert.auth_service.dto.UserResponseData;
import dev.yolbert.auth_service.service.RegisterUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user")
public class UserController {

    private final RegisterUseCase registerUseCase;

    public UserController(RegisterUseCase registerUseCase) {
        this.registerUseCase = registerUseCase;
    }

    /**
     * POST /user/
     * Registers a new user account.
     *
     * @param command validated registration data
     * @return 201 Created with the new user's public data
     */
    @PostMapping("/")
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
}
