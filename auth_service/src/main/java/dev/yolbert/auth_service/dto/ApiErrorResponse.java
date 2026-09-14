package dev.yolbert.auth_service.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ApiErrorResponse {

    private final String status = "error";
    private String message;
    private List<ApiErrorDetail> errors;
}
