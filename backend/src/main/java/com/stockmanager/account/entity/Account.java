package com.stockmanager.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("account")
public class Account {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private String accountNo;
    private String accountName;
    private String broker;
    private String environment;
    private String currency;
    private BigDecimal totalAsset;
    private BigDecimal cash;
    private BigDecimal marketValue;
    private BigDecimal profitLoss;
    private LocalDateTime lastSyncTime;
    private String status;
}
