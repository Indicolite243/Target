package com.stockmanager.common.response;

import java.time.OffsetDateTime;

/**
 * 全部业务 API 的统一响应信封。
 *
 * @param code 业务码，0 表示成功
 * @param message 可直接展示或用于诊断的简要消息
 * @param data 具体业务数据
 * @param traceId 当前请求链路追踪 ID
 * @param timestamp 服务端生成响应的时间
 */
public record ApiResponse<T>(
        int code,
        String message,
        T data,
        String traceId,
        OffsetDateTime timestamp
) {
    /** 创建使用默认 success 消息的成功响应。 */
    public static <T> ApiResponse<T> success(T data, String traceId) {
        return new ApiResponse<>(0, "success", data, traceId, OffsetDateTime.now());
    }

    /** 创建使用自定义消息的成功响应。 */
    public static <T> ApiResponse<T> success(String message, T data, String traceId) {
        return new ApiResponse<>(0, message, data, traceId, OffsetDateTime.now());
    }

    /** 创建包含业务错误码和可选详情的失败响应。 */
    public static ApiResponse<Object> failure(int code, String message, Object data, String traceId) {
        return new ApiResponse<>(code, message, data, traceId, OffsetDateTime.now());
    }
}
