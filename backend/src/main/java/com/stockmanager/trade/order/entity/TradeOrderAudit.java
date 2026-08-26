package com.stockmanager.trade.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("trade_order_audit")
public class TradeOrderAudit {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long orderId;
    private Long userId;
    private String action;
    private String idempotencyKey;
    private String source;
    private String detailJson;
    private LocalDateTime createdAt;
}
