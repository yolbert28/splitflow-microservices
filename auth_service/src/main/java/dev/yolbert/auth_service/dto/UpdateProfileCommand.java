package dev.yolbert.auth_service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.yolbert.auth_service.utils.validation.ValidFullName;
import dev.yolbert.auth_service.utils.validation.ValidPhotoUrl;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public class UpdateProfileCommand {

    @ValidFullName
    @JsonProperty("full_name")
    private String fullName;

    @Email(message = "El formato del email no es válido.")
    @Size(max = 254, message = "El email no puede superar los 254 caracteres.")
    private String email;

    private String photoUrl;
    private boolean photoUrlSet;

    public UpdateProfileCommand() {}

    public UpdateProfileCommand(String fullName, String email, String photoUrl) {
        this.fullName = fullName;
        this.email = email;
        setPhotoUrl(photoUrl);
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    @ValidPhotoUrl
    public String getPhotoUrl() {
        return photoUrl;
    }

    @JsonProperty("photo_url")
    public void setPhotoUrl(String photoUrl) {
        this.photoUrl = photoUrl;
        this.photoUrlSet = true;
    }

    public boolean isPhotoUrlSet() {
        return photoUrlSet;
    }
}
