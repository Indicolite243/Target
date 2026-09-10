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
    // 使用应用侧生成的主键，便于插入持仓明细时关联父快照。
    @TableId(type = IdType.ASSIGN_ID)
    // 当前历史快照的唯一 ID。
    private Long id;
    // 快照所属账户 ID。
    private Long accountId;
    // 采集快照时的业务时间。
    private LocalDateTime snapshotTime;
    // 快照类型，例如日内、手动或定时采集。
    private String snapshotType;
    // 快照时账户的总资产。
    private BigDecimal totalAsset;
    // 快照时的可用现金。
    private BigDecimal cash;
    // 快照时持仓的总市值。
    private BigDecimal marketValue;
    // 快照时累计浮动盈亏。
    private BigDecimal profitLoss;
    // 快照包含的持仓行数量。
    private Integer positionCount;
    // 是否同时保存了完整持仓明细。
    private Boolean positionsCaptured;
    // 数据来源，例如 QMT 或模拟器。
    private String source;
    // 用于幂等去重的数据版本。
    private String dataVersion;
    // 一次性迁移期间保留的 MongoDB _id，新记录通常为 null。
    private String sourceRecordId;
    // 该历史记录写入 MySQL 的时间。
    private LocalDateTime createdAt;
}
