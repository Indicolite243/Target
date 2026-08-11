package com.stockmanager.trade.order.service;

import com.stockmanager.trade.order.dto.SubmitOrderRequest;
import com.stockmanager.trade.order.vo.OrderView;

import java.util.List;

public interface OrderService {
    OrderView submit(Long userId, String idempotencyKey, SubmitOrderRequest request, String traceId);
    List<OrderView> list(Long userId, Long accountId);
    OrderView cancel(Long userId, Long orderId, String idempotencyKey, String traceId);
}
