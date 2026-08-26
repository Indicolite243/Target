package com.stockmanager.account.service;

import com.stockmanager.account.vo.AccountView;
import com.stockmanager.account.vo.AccountSyncView;
import com.stockmanager.account.vo.PositionView;
import com.stockmanager.account.entity.AccountHistorySnapshot;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface AccountService {
    List<AccountView> listAccounts(Long userId);
    AccountView getAccount(Long userId, Long accountId);
    List<PositionView> listPositions(Long userId, Long accountId);
    AccountSyncView syncAccount(Long userId, Long accountId, String traceId);
    List<AccountHistorySnapshot> listSnapshots(Long userId, Long accountId, LocalDate from, LocalDate to);
    Map<String, Object> qmtStatus(String traceId);
}
