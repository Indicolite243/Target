package com.stockmanager.account.vo;

import java.time.LocalDateTime;
import java.util.List;

/** 一次账户同步的完整返回，包含账户、全量持仓、来源、快照时间和非致命警告。 */
public record AccountSyncView(
        // 本次同步后的账户资产摘要。
        AccountView account,
        // 本次同步得到的完整持仓列表。
        List<PositionView> positions,
        // 数据来源，例如 qmt 或 simulator。
        String source,
        // 上游快照对应的业务时间。
        LocalDateTime snapshotTime,
        // 不影响同步成功、但需要提示前端的警告信息。
        List<String> warnings) {
    // record 自动生成构造方法和字段访问器。
}
