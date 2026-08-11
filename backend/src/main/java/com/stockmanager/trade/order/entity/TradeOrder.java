package com.stockmanager.trade.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("trade_order")
public class TradeOrder {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long accountId;
    private Long userId;
    private String idempotencyKey;
    private String clientOrderNo;
    private String externalOrderNo;
    private String symbol;
    private String securityName;
    private String side;
    private String orderType;
    private BigDecimal quantity;
    private BigDecimal price;
    private BigDecimal filledQuantity;
    private BigDecimal averageFilledPrice;
    private String status;
    private String environment;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
