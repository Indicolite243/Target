package com.stockmanager.trade.order.vo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 订单状态历史与操作审计的组合视图。
 *
 * <p>时间线把“状态变化”和“用户/系统操作”分成两个列表，前端可分别展示状态轨迹和审计事件。</p>
 */
public record OrderTimelineView(String orderId, List<StatusItem> statusHistory, List<AuditItem> audit) {
    /** 一次订单状态或成交字段变化，按observedAt升序返回。 */
    public record StatusItem(String previousStatus, String currentStatus, String filledQuantity,
                             String averageFilledPrice, String source, String brokerMessage,
                             LocalDateTime observedAt) {
    }

    /** 用户可见审计项；有意省略幂等键和其他内部敏感定位信息。 */
    public record AuditItem(String action, String source, Map<String, Object> detail, LocalDateTime createdAt) {
    }
}
