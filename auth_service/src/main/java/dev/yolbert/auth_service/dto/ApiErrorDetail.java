package dev.yolbert.auth_service.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ApiErrorDetail {

    private String field;
    private String message;
}
