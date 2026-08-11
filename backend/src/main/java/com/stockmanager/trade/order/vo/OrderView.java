package com.stockmanager.trade.order.vo;

import java.time.LocalDateTime;

public record OrderView(String orderId, String clientOrderNo, String externalOrderNo, String accountId,
                        String symbol, String securityName, String side, String orderType, String quantity,
                        String price, String filledQuantity, String averageFilledPrice, String status,
                        String environment, LocalDateTime createdAt, LocalDateTime updatedAt, String message) {
}
