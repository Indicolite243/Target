package com.stockmanager.common.response;

import java.time.OffsetDateTime;

public record ApiResponse<T>(
        int code,
        String message,
        T data,
        String traceId,
        OffsetDateTime timestamp
) {
    public static <T> ApiResponse<T> success(T data, String traceId) {
        return new ApiResponse<>(0, "success", data, traceId, OffsetDateTime.now());
    }

    public static <T> ApiResponse<T> success(String message, T data, String traceId) {
        return new ApiResponse<>(0, message, data, traceId, OffsetDateTime.now());
    }

    public static ApiResponse<Object> failure(int code, String message, Object data, String traceId) {
        return new ApiResponse<>(code, message, data, traceId, OffsetDateTime.now());
    }
}
