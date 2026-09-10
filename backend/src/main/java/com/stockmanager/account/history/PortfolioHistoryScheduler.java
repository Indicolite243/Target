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

/**
 * 把 Redis 中的实时快照复制为可审计的历史快照；本类不直接依赖 QMT。
 *
 * <pre>
 * 盘中：Redis 快照 --5分钟一次--> INTRADAY 历史（持仓有变化才写）
 * 收盘：Redis 快照 --15:10------> DAILY 历史（强制保留持仓）
 * </pre>
 */
@Component
@ConditionalOnProperty(prefix = "app.portfolio-history", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PortfolioHistoryScheduler {
    private static final Logger log = LoggerFactory.getLogger(PortfolioHistoryScheduler.class);
    private final PortfolioLiveService liveService;
    private final PortfolioHistoryPersistenceService persistenceService;
    private final ReentrantLock captureLock = new ReentrantLock();

    /** 注入实时组合读取和历史持久化服务。 */
    public PortfolioHistoryScheduler(PortfolioLiveService liveService,
                                     PortfolioHistoryPersistenceService persistenceService) {
        this.liveService = liveService;
        this.persistenceService = persistenceService;
    }

    @Scheduled(fixedDelayString = "${app.portfolio-history.asset-interval-ms:300000}",
            initialDelayString = "${app.portfolio-history.initial-delay-ms:60000}")
    /** 交易时段按资产采样频率归档账户资产摘要。 */
    public void captureIntradayAssets() {
        // 非交易时段不产生盘中噪声；周末也不写入无意义快照。
        if (isTradingWindow()) captureAll("INTRADAY", false);
    }

    /** 收盘后强制保存一份带完整持仓的日终快照，即使持仓内容没有变化。 */
    @Scheduled(cron = "${app.portfolio-history.daily-cron:0 10 15 * * MON-FRI}", zone = "Asia/Shanghai")
    public void captureDailyClosingSnapshot() {
        captureAll("DAILY", true);
    }

    private void captureAll(String snapshotType, boolean forcePositions) {
        // 调度器可能因执行时间超过周期而重入；本地锁只负责单实例防重入。
        if (!captureLock.tryLock()) {
            log.debug("历史快照任务仍在运行，跳过本轮 type={}", snapshotType);
            return;
        }
        try {
            // 只遍历Redis/内存中的最新QMT快照，不在历史线程中再次调用券商接口。
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
        // 使用本机Asia/Shanghai部署时间判断；定时任务自身的收盘cron已显式指定时区。
        LocalDateTime now = LocalDateTime.now();
        if (now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY) return false;
        int minutes = now.getHour() * 60 + now.getMinute();
        return minutes >= 9 * 60 + 15 && minutes <= 15 * 60 + 30;
    }
}
