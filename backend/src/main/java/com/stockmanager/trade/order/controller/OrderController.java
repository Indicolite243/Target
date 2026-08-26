package com.stockmanager.trade.order.controller;

import com.stockmanager.common.response.ApiResponse;
import com.stockmanager.trade.order.dto.CancelOrderRequest;
import com.stockmanager.trade.order.dto.SubmitOrderRequest;
import com.stockmanager.trade.order.service.OrderService;
import com.stockmanager.trade.order.vo.OrderPageView;
import com.stockmanager.trade.order.vo.OrderTimelineView;
import com.stockmanager.trade.order.vo.OrderView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

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
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "50") long pageSize,
                                                 @RequestParam(name = "start_date", required = false) String startDate,
                                                 @RequestParam(name = "end_date", required = false) String endDate,
                                                 Authentication authentication, HttpServletRequest request) {
        OrderPageView result = orderService.list(userId(authentication), accountId, page, pageSize,
                parseDate(startDate, false), parseDate(endDate, true));
        return ApiResponse.success(Map.of("items", result.items(), "page", result.page(), "pageSize", result.pageSize(),
                "total", result.total(), "totalPages", result.totalPages(), "hasNext", result.hasNext()), traceId(request));
    }

    /** Removes a saved history row only; it does not send a cancel request to QMT. */
    @DeleteMapping("/{orderId}")
    public ApiResponse<Map<String, Object>> deleteHistory(@PathVariable Long orderId,
                                                           Authentication authentication,
                                                           HttpServletRequest request) {
        orderService.deleteHistory(userId(authentication), orderId, traceId(request));
        return ApiResponse.success(Map.of("deleted", 1), traceId(request));
    }

    /** Soft-deletes all visible history rows in the optional date range. */
    @DeleteMapping("/history")
    public ApiResponse<Map<String, Object>> deleteFilteredHistory(
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            Authentication authentication, HttpServletRequest request) {
        int deleted = orderService.deleteFilteredHistory(userId(authentication), parseDate(startDate, false),
                parseDate(endDate, true), traceId(request));
        return ApiResponse.success(Map.of("deleted", deleted), traceId(request));
    }

    @GetMapping("/{orderId}/timeline")
    public ApiResponse<OrderTimelineView> timeline(@PathVariable Long orderId, Authentication authentication,
                                                    HttpServletRequest request) {
        return ApiResponse.success(orderService.timeline(userId(authentication), orderId), traceId(request));
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
    private LocalDateTime parseDate(String value, boolean endExclusive) {
        if (value == null || value.isBlank()) return null;
        try {
            LocalDate date = LocalDate.parse(value);
            return (endExclusive ? date.plusDays(1) : date).atStartOfDay();
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日期格式必须为 YYYY-MM-DD");
        }
    }
    private String traceId(HttpServletRequest request) { return String.valueOf(request.getAttribute("traceId")); }
}
