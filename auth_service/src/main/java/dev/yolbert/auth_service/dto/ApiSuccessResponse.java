package dev.yolbert.auth_service.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ApiSuccessResponse<T> {

    private final String status = "success";
    private String message;
    private T data;
}
