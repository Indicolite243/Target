package com.stockmanager.trade.order.controller;

import com.stockmanager.common.response.ApiResponse;
import com.stockmanager.trade.order.dto.CancelOrderRequest;
import com.stockmanager.trade.order.dto.SubmitOrderRequest;
import com.stockmanager.trade.order.service.OrderService;
import com.stockmanager.trade.order.vo.OrderView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) { this.orderService = orderService; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderView> submit(@RequestHeader("X-Idempotency-Key") String idempotencyKey,
                                         @Valid @RequestBody SubmitOrderRequest body,
                                         Authentication authentication, HttpServletRequest request) {
        if (idempotencyKey.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少幂等键");
        return ApiResponse.success("委托已受理", orderService.submit(userId(authentication), idempotencyKey,
                body, traceId(request)), traceId(request));
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) Long accountId,
                                                 Authentication authentication, HttpServletRequest request) {
        List<OrderView> items = orderService.list(userId(authentication), accountId);
        return ApiResponse.success(Map.of("items", items, "page", 1, "pageSize", items.size(),
                "total", items.size(), "totalPages", 1, "hasNext", false), traceId(request));
    }

    @PostMapping("/{orderId}/cancel")
    public ApiResponse<OrderView> cancel(@PathVariable Long orderId,
                                         @RequestHeader("X-Idempotency-Key") String idempotencyKey,
                                         @RequestBody(required = false) CancelOrderRequest body,
                                         Authentication authentication, HttpServletRequest request) {
        return ApiResponse.success("撤单请求已受理", orderService.cancel(userId(authentication), orderId,
                idempotencyKey, traceId(request)), traceId(request));
    }

    private Long userId(Authentication authentication) { return Long.valueOf(authentication.getName()); }
    private String traceId(HttpServletRequest request) { return String.valueOf(request.getAttribute("traceId")); }
}
