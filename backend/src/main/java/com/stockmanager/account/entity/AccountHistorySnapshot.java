package com.stockmanager.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Immutable asset-level portfolio history stored in MySQL. */
@Data
@TableName("account_snapshot")
public class AccountHistorySnapshot {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long accountId;
    private LocalDateTime snapshotTime;
    private String snapshotType;
    private BigDecimal totalAsset;
    private BigDecimal cash;
    private BigDecimal marketValue;
    private BigDecimal profitLoss;
    private Integer positionCount;
    private Boolean positionsCaptured;
    private String source;
    private String dataVersion;
    /** MongoDB _id while the one-time migration is in progress; null for new records. */
    private String sourceRecordId;
    private LocalDateTime createdAt;
}
