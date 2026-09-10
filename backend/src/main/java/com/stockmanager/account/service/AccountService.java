package com.stockmanager.account.service;

import com.stockmanager.account.vo.AccountView;
import com.stockmanager.account.vo.AccountSyncView;
import com.stockmanager.account.vo.PositionView;
import com.stockmanager.account.entity.AccountHistorySnapshot;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 账户领域对外服务契约，所有带账户 ID 的方法都必须校验用户资源归属。 */
public interface AccountService {
    /** 查询用户账户摘要列表。 */
    List<AccountView> listAccounts(Long userId);
    /** 查询用户拥有的单个账户。 */
    AccountView getAccount(Long userId, Long accountId);
    /** 查询账户当前持仓。 */
    List<PositionView> listPositions(Long userId, Long accountId);
    /** 从 QMT 同步账户和持仓到 MySQL。 */
    AccountSyncView syncAccount(Long userId, Long accountId, String traceId);
    /** 查询账户历史快照。 */
    List<AccountHistorySnapshot> listSnapshots(Long userId, Long accountId, LocalDate from, LocalDate to);
    /** 查询 QMT 连接和交易开关状态。 */
    Map<String, Object> qmtStatus(String traceId);
}
