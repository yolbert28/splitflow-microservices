package dev.yolbert.auth_service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
public class VerifyEmailResponseData {

    private UUID id;
    private String email;
    private boolean verified;

    @JsonProperty("verified_at")
    private LocalDateTime verifiedAt;
}
