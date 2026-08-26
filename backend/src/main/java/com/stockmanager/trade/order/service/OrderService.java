package com.stockmanager.trade.order.service;

import com.stockmanager.trade.order.dto.SubmitOrderRequest;
import com.stockmanager.trade.order.vo.OrderPageView;
import com.stockmanager.trade.order.vo.OrderTimelineView;
import com.stockmanager.trade.order.vo.OrderView;
import java.time.LocalDateTime;

public interface OrderService {
    OrderView submit(Long userId, String idempotencyKey, SubmitOrderRequest request, String traceId);
    OrderPageView list(Long userId, Long accountId, long page, long pageSize, LocalDateTime start, LocalDateTime endExclusive);
    void deleteHistory(Long userId, Long orderId, String traceId);
    int deleteFilteredHistory(Long userId, LocalDateTime start, LocalDateTime endExclusive, String traceId);
    OrderView cancel(Long userId, Long orderId, String idempotencyKey, String traceId);
    int refreshOpenOrderStatuses();
    OrderTimelineView timeline(Long userId, Long orderId);
}
