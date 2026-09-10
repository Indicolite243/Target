package com.stockmanager.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 可预期业务错误，携带稳定业务码、对外消息和对应 HTTP 状态。
 *
 * <p>该异常用于权限归属、资源不存在、状态冲突和上游明确拒绝等场景，不用于隐藏编程错误。</p>
 */
@Getter
public class BusinessException extends RuntimeException {
    private final int code;
    private final HttpStatus status;

    /** 创建一项可由全局异常处理器安全返回给前端的业务异常。 */
    public BusinessException(int code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }
}
