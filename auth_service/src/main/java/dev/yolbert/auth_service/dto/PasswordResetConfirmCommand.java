package dev.yolbert.auth_service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.yolbert.auth_service.utils.validation.PasswordsMatch;
import dev.yolbert.auth_service.utils.validation.ValidPassword;
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
@PasswordsMatch
public class PasswordResetConfirmCommand {

    @NotBlank(message = "El email es obligatorio.")
    @Email(message = "El formato del email no es válido.")
    private String email;

    @NotBlank(message = "El código OTP es obligatorio.")
    @JsonProperty("otp_code")
    private String otpCode;

    @NotBlank(message = "La nueva contraseña es obligatoria.")
    @ValidPassword
    @JsonProperty("new_password")
    private String newPassword;

    @NotBlank(message = "La confirmación de la nueva contraseña es obligatoria.")
    @JsonProperty("confirm_new_password")
    private String confirmNewPassword;
}
