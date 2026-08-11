package com.stockmanager.account.vo;

import java.time.LocalDateTime;
import java.util.List;

public record AccountSyncView(AccountView account,
                              List<PositionView> positions,
                              String source,
                              LocalDateTime snapshotTime,
                              List<String> warnings) {
}
