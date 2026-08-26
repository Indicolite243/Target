package com.stockmanager.account.history;

import com.stockmanager.account.live.CurrentPortfolioSnapshot;
import com.stockmanager.account.live.PortfolioLiveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.concurrent.locks.ReentrantLock;

/** Copies cached Redis data to immutable MySQL history; it has no QMT dependency. */
@Component
@ConditionalOnProperty(prefix = "app.portfolio-history", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PortfolioHistoryScheduler {
    private static final Logger log = LoggerFactory.getLogger(PortfolioHistoryScheduler.class);
    private final PortfolioLiveService liveService;
    private final PortfolioHistoryPersistenceService persistenceService;
    private final ReentrantLock captureLock = new ReentrantLock();

    public PortfolioHistoryScheduler(PortfolioLiveService liveService,
                                     PortfolioHistoryPersistenceService persistenceService) {
        this.liveService = liveService;
        this.persistenceService = persistenceService;
    }

    @Scheduled(fixedDelayString = "${app.portfolio-history.asset-interval-ms:300000}",
            initialDelayString = "${app.portfolio-history.initial-delay-ms:60000}")
    public void captureIntradayAssets() {
        if (isTradingWindow()) captureAll("INTRADAY", false);
    }

    /** One complete closing snapshot is retained even when positions did not change. */
    @Scheduled(cron = "${app.portfolio-history.daily-cron:0 10 15 * * MON-FRI}", zone = "Asia/Shanghai")
    public void captureDailyClosingSnapshot() {
        captureAll("DAILY", true);
    }

    private void captureAll(String snapshotType, boolean forcePositions) {
        if (!captureLock.tryLock()) {
            log.debug("历史快照任务仍在运行，跳过本轮 type={}", snapshotType);
            return;
        }
        try {
            for (CurrentPortfolioSnapshot snapshot : liveService.cachedQmtSnapshots()) {
                try {
                    persistenceService.persistLiveSnapshot(snapshot, snapshotType, forcePositions);
                } catch (Exception ex) {
                    log.warn("MySQL历史快照写入失败，accountId={}, type={}, reason={}",
                            snapshot.accountId(), snapshotType, ex.getMessage());
                }
            }
        } finally {
            captureLock.unlock();
        }
    }

    private boolean isTradingWindow() {
        LocalDateTime now = LocalDateTime.now();
        if (now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY) return false;
        int minutes = now.getHour() * 60 + now.getMinute();
        return minutes >= 9 * 60 + 15 && minutes <= 15 * 60 + 30;
    }
}
