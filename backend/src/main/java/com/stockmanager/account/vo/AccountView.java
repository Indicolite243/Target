package com.stockmanager.account.vo;

import java.time.LocalDateTime;

/**
 * 账户展示模型。金额使用十进制字符串；同时保留外部账号和脱敏账号供受控场景与页面展示使用。
 * stale 表示最近同步时间已超过实时性阈值。
 */
public record AccountView(
        // 系统内部账户 ID。
        String accountId,
        // 券商或外部系统账户 ID。
        String externalAccountId,
        // 对外展示的脱敏账户号。
        String accountNoMasked,
        // 账户展示名称。
        String accountName,
        // 券商或适配器类型。
        String broker,
        // 账户运行环境，例如 SIMULATION。
        String environment,
        // 资金币种。
        String currency,
        // 格式化后的总资产金额。
        String totalAsset,
        // 格式化后的可用资金。
        String cash,
        // 格式化后的持仓市值。
        String marketValue,
        // 格式化后的浮动盈亏。
        String profitLoss,
        // 最近一次成功同步时间。
        LocalDateTime lastSyncTime,
        // 最近同步超过实时性阈值时为 true。
        boolean stale,
        // 账户业务状态。
        String status) {
    // record 自动生成构造方法和字段访问器。
}
