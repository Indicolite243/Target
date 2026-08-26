package com.stockmanager.account.live;

import com.stockmanager.account.vo.AccountView;
import com.stockmanager.account.vo.PositionView;

import java.time.LocalDateTime;
import java.util.List;

/** One coherent current-portfolio version consumed by every live page widget. */
public record CurrentPortfolioSnapshot(
        String accountId,
        long dataVersion,
        String source,
        String mode,
        boolean stale,
        LocalDateTime snapshotTime,
        long ageMs,
        AccountView account,
        List<PositionView> positions,
        List<String> warnings
) {
}
