package com.stockmanager.common.controller;

import com.stockmanager.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 提供不依赖业务数据的轻量应用存活检查接口。
 *
 * <p>该接口只说明 Spring 进程能够接收并返回 HTTP 请求，不查询 MySQL、Redis 或 QMT，
 * 因此不会因为外部依赖短暂故障把“应用存活”误判为“应用宕机”。依赖级健康状态由
 * Actuator 或单独的就绪检查负责。</p>
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {
    /**
     * 返回固定服务名和 UP 状态，并复用 TraceIdFilter 已写入请求属性的 traceId。
     * 该接口位于安全白名单，可供 Docker HEALTHCHECK、网关和人工排障调用。
     */
    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health(HttpServletRequest request) {
        // 统一包进 ApiResponse，使健康接口与业务接口拥有相同的响应和链路追踪格式。
        return ApiResponse.success(Map.of("service", "stock-manager-backend", "status", "UP"),
                String.valueOf(request.getAttribute("traceId")));
    }
}
