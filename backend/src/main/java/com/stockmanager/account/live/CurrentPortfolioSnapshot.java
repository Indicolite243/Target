package com.stockmanager.account.live;

import com.stockmanager.account.vo.AccountView;
import com.stockmanager.account.vo.PositionView;

import java.time.LocalDateTime;
import java.util.List;

/** One coherent current-portfolio version consumed by every live page widget. */
// 表示同一数据版本下的完整实时组合，保证页面各组件看到一致的账户和持仓数据。
public record CurrentPortfolioSnapshot(
        // 系统内部账户 ID。
        String accountId,
        // 组合数据版本；版本变化表示 Redis 中的完整快照已更新。
        long dataVersion,
        // 快照来源，例如 QMT 或 SIMULATOR。
        String source,
        // 快照模式，例如 LIVE 或 FALLBACK。
        String mode,
        // 数据超过实时阈值或来自降级数据时为 true。
        boolean stale,
        // 上游生成该快照的时间。
        LocalDateTime snapshotTime,
        // 当前时间距离快照时间的毫秒数。
        long ageMs,
        // 同一版本中的账户资产摘要。
        AccountView account,
        // 同一版本中的完整持仓列表。
        List<PositionView> positions,
        // 刷新或降级过程中产生的非致命警告。
        List<String> warnings
) {
    // record 自动生成不可变字段访问器和构造方法。
}
