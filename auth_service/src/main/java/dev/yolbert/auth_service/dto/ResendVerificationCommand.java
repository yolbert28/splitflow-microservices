package dev.yolbert.auth_service.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResendVerificationCommand {

    @NotBlank(message = "El email es obligatorio.")
    @Email(message = "El formato del email no es válido.")
    private String email;
}
