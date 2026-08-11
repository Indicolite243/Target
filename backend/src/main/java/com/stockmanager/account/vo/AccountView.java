package com.stockmanager.account.vo;

import java.time.LocalDateTime;

public record AccountView(String accountId, String externalAccountId, String accountNoMasked, String accountName, String broker,
                          String environment, String currency, String totalAsset, String cash,
                          String marketValue, String profitLoss, LocalDateTime lastSyncTime,
                          boolean stale, String status) {
}
