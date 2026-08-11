package com.stockmanager.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("position")
public class Position {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long accountId;
    private String securityCode;
    private String securityName;
    private BigDecimal quantity;
    private BigDecimal availableQuantity;
    private BigDecimal costPrice;
    private BigDecimal lastPrice;
    private BigDecimal marketValue;
    private BigDecimal profitLoss;
    private String industry;
    private String region;
}
