package dev.yolbert.auth_service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
public class UserResponseData {

    private UUID id;

    @JsonProperty("full_name")
    private String fullName;

    private String email;

    @JsonProperty("friend_code")
    private String friendCode;

    private boolean verified;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;
}
