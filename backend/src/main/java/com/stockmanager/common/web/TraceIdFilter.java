package com.stockmanager.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 为每个 HTTP 请求建立统一链路追踪 ID 的 Servlet 过滤器。
 *
 * <p>优先复用前端发送的 {@code X-Request-Id}，缺失时生成 UUID；追踪 ID 同时写入请求属性、
 * 响应头和日志 MDC，并在请求结束后清理线程上下文，防止线程复用造成串号。</p>
 */
@Component
public class TraceIdFilter extends OncePerRequestFilter {
    /** 在完整过滤器链执行期间安装并维护当前请求的 traceId。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader("X-Request-Id");
        if (traceId == null || traceId.isBlank()) {
            // 去掉 UUID 中的连字符，生成便于日志检索和跨服务透传的 32 位标识。
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        // Controller/异常处理器通过请求属性取得同一个 traceId，无需重复解析请求头。
        request.setAttribute("traceId", traceId);
        // 前端可以从响应头取得 traceId，在报错提示中提供给后端排查。
        response.setHeader("X-Trace-Id", traceId);
        // MDC 让本请求产生的日志自动携带 traceId；日志格式需包含 %X{traceId}。
        MDC.put("traceId", traceId);
        try {
            // 继续执行 JWT、Controller 等后续过滤器和业务逻辑。
            filterChain.doFilter(request, response);
        } finally {
            // Tomcat 会复用工作线程，不清理 MDC 会导致下一请求错误继承上一请求的标识。
            MDC.remove("traceId");
        }
    }
}
