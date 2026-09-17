package dev.yolbert.auth_service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.yolbert.auth_service.utils.validation.PasswordsMatch;
import dev.yolbert.auth_service.utils.validation.ValidPassword;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@PasswordsMatch
public class ChangePasswordCommand {

    @NotBlank(message = "La contraseña actual es obligatoria.")
    @JsonProperty("current_password")
    private String currentPassword;

    @NotBlank(message = "La nueva contraseña es obligatoria.")
    @ValidPassword
    @JsonProperty("new_password")
    private String newPassword;

    @NotBlank(message = "La confirmación de la nueva contraseña es obligatoria.")
    @JsonProperty("confirm_new_password")
    private String confirmNewPassword;
}
