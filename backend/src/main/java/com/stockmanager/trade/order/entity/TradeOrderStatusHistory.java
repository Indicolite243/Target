package com.stockmanager.trade.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("trade_order_status_history")
public class TradeOrderStatusHistory {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long orderId;
    private String previousStatus;
    private String currentStatus;
    private BigDecimal filledQuantity;
    private BigDecimal averageFilledPrice;
    private String source;
    private String brokerMessage;
    private LocalDateTime observedAt;
    private LocalDateTime createdAt;
}
