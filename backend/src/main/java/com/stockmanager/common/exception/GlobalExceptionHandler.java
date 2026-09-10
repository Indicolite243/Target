package com.stockmanager.common.exception;

import com.stockmanager.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常到 HTTP 响应的转换器，保证前端始终收到统一 {@link ApiResponse} 结构。
 *
 * <p>已知业务错误保留业务码和 HTTP 状态；参数错误返回字段级信息；未知异常不向客户端暴露
 * 堆栈、数据库或外部服务细节。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    /** 转换业务层主动抛出的可预期异常。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Object>> handleBusiness(BusinessException ex, HttpServletRequest request) {
        // status 决定 HTTP 语义，code 决定前端可识别的细分业务原因，两者不可混为一个字段。
        return ResponseEntity.status(ex.getStatus())
                .body(ApiResponse.failure(ex.getCode(), ex.getMessage(), null, traceId(request)));
    }

    /** 汇总请求体 Bean Validation 的字段错误。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidation(MethodArgumentNotValidException ex,
                                                                  HttpServletRequest request) {
        Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
                // 同一字段存在多条约束时只保留第一条，避免 toMap 因重复 key 抛异常。
                .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage, (a, b) -> a));
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure(400100, "请求参数校验失败", Map.of("fieldErrors", errors), traceId(request)));
    }

    /** 处理路径参数和查询参数上的约束错误。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Object>> handleConstraint(ConstraintViolationException ex,
                                                                  HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure(400100, ex.getMessage(), null, traceId(request)));
    }

    /** 将未分类异常收敛为不泄露内部实现的 500 响应。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleUnknown(Exception ex, HttpServletRequest request) {
        // 响应不返回 ex.getMessage()，防止 SQL、路径、令牌或第三方错误细节泄露给浏览器。
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failure(500001, "系统内部错误", null, traceId(request)));
    }

    /** 从请求属性读取 TraceIdFilter 已生成的链路标识。 */
    private String traceId(HttpServletRequest request) {
        // TraceIdFilter 位于请求链前部，正常情况下该属性始终存在；String.valueOf 同时兼容空值。
        return String.valueOf(request.getAttribute("traceId"));
    }
}
