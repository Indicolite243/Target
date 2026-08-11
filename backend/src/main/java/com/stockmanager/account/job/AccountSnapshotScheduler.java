package com.stockmanager.account.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stockmanager.account.entity.Account;
import com.stockmanager.account.mapper.AccountMapper;
import com.stockmanager.account.service.AccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodically captures the live QMT account state.  MySQL remains the
 * current-state store while AccountService appends an immutable MongoDB
 * snapshot for historical analysis.
 */
@Component
@ConditionalOnProperty(prefix = "app.snapshot", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AccountSnapshotScheduler {
    private static final Logger log = LoggerFactory.getLogger(AccountSnapshotScheduler.class);

    private final AccountMapper accountMapper;
    private final AccountService accountService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AccountSnapshotScheduler(AccountMapper accountMapper, AccountService accountService) {
        this.accountMapper = accountMapper;
        this.accountService = accountService;
    }

    @Scheduled(
            fixedDelayString = "${app.snapshot.account-sync-interval-ms:30000}",
            initialDelayString = "${app.snapshot.initial-delay-ms:10000}"
    )
    public void captureAllQmtAccounts() {
        if (!isTradingWindow()) return;
        captureAllQmtAccounts("INTRADAY");
    }

    /** One immutable closing snapshot per account and trading day. */
    @Scheduled(cron = "${app.snapshot.daily-cron:0 10 15 * * MON-FRI}", zone = "Asia/Shanghai")
    public void captureDailyClosingSnapshots() {
        captureAllQmtAccounts("DAILY");
    }

    private void captureAllQmtAccounts(String snapshotType) {
        if (!running.compareAndSet(false, true)) {
            log.warn("上一轮 QMT 账户快照仍在执行，跳过本轮采集");
            return;
        }
        try {
            List<Account> accounts = accountMapper.selectList(Wrappers.<Account>lambdaQuery()
                    .eq(Account::getBroker, "GUOJIN_QMT")
                    .ne(Account::getStatus, "DISABLED"));
            for (Account account : accounts) {
                try {
                    accountService.syncAccountScheduled(account.getUserId(), account.getId(),
                            "scheduled-snapshot-" + snapshotType.toLowerCase() + "-" + account.getId(),
                            snapshotType);
                    log.info("QMT 账户快照已采集: accountId={}, accountNo={}",
                            account.getId(), account.getAccountNo());
                } catch (Exception ex) {
                    // One disconnected account must not prevent other accounts from syncing.
                    log.warn("QMT 账户快照采集失败: accountId={}, reason={}", account.getId(), ex.getMessage());
                }
            }
        } finally {
            running.set(false);
        }
    }

    private boolean isTradingWindow() {
        LocalDateTime now = LocalDateTime.now();
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        int minutes = now.getHour() * 60 + now.getMinute();
        return minutes >= 9 * 60 + 15 && minutes <= 15 * 60 + 30;
    }
}
