package com.example.ekb.common.response;

import java.time.Instant;

import com.example.ekb.common.enums.ErrorCode;
import com.example.ekb.common.utils.RequestIdHolder;

public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data,
        String requestId,
        String timestamp
) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(
                true,
                ErrorCode.SUCCESS.getCode(),
                ErrorCode.SUCCESS.getDefaultMessage(),
                data,
                RequestIdHolder.getRequestId(),
                Instant.now().toString()
        );
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.getDefaultMessage(), null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return fail(errorCode, message, null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message, T data) {
        return new ApiResponse<>(
                false,
                errorCode.getCode(),
                message,
                data,
                RequestIdHolder.getRequestId(),
                Instant.now().toString()
        );
    }
}
