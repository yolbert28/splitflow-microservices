package dev.yolbert.auth_service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogoutCommand {

    @NotBlank(message = "El refresh token es obligatorio.")
    @JsonProperty("refresh_token")
    private String refreshToken;
}