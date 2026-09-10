package com.stockmanager.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Position rows belonging to one immutable account history snapshot. */
@Data
@TableName("position_snapshot")
public class PositionHistorySnapshot {
    // 使用应用侧生成持仓历史明细主键。
    @TableId(type = IdType.ASSIGN_ID)
    // 持仓历史明细主键。
    private Long id;
    // 所属账户历史快照 ID。
    private Long snapshotId;
    // 所属账户 ID，便于查询和归属校验。
    private Long accountId;
    // 与父快照一致的业务采集时间。
    private LocalDateTime snapshotTime;
    // 标准证券代码。
    private String securityCode;
    // 证券展示名称。
    private String securityName;
    // 快照时的总持仓数量。
    private BigDecimal quantity;
    // 快照时的可卖数量。
    private BigDecimal availableQuantity;
    // 持仓成本价。
    private BigDecimal costPrice;
    // 快照时的最新价格。
    private BigDecimal lastPrice;
    // 快照时的持仓市值。
    private BigDecimal marketValue;
    // 快照时的持仓盈亏。
    private BigDecimal profitLoss;
    // 行业分类，可为空。
    private String industry;
    // 市场或地区分类，可为空。
    private String region;
    // 明细写入时间。
    private LocalDateTime createdAt;
}
