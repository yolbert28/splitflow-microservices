package dev.yolbert.auth_service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.yolbert.auth_service.utils.validation.PasswordsMatch;
import dev.yolbert.auth_service.utils.validation.ValidFullName;
import dev.yolbert.auth_service.utils.validation.ValidPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@PasswordsMatch
public class RegisterUserCommand {

    @NotBlank(message = "El nombre completo es obligatorio.")
    @ValidFullName
    @JsonProperty("full_name")
    private String fullName;

    @NotBlank(message = "El email es obligatorio.")
    @Email(message = "El formato del email no es válido.")
    @Size(max = 254, message = "El email no puede superar los 254 caracteres.")
    private String email;

    @NotBlank(message = "La contraseña es obligatoria.")
    @ValidPassword
    private String password;

    @NotBlank(message = "La confirmación de contraseña es obligatoria.")
    @JsonProperty("confirm_password")
    private String confirmPassword;
}
