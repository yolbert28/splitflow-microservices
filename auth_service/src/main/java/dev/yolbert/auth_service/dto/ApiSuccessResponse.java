package dev.yolbert.auth_service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiSuccessResponse<T> {

    private final String status = "success";
    private String message;
    private T data;
}
