package com.stockmanager.trade.order.vo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record OrderTimelineView(String orderId, List<StatusItem> statusHistory, List<AuditItem> audit) {
    public record StatusItem(String previousStatus, String currentStatus, String filledQuantity,
                             String averageFilledPrice, String source, String brokerMessage,
                             LocalDateTime observedAt) {
    }

    /** Idempotency keys are intentionally omitted from the user-facing audit projection. */
    public record AuditItem(String action, String source, Map<String, Object> detail, LocalDateTime createdAt) {
    }
}
