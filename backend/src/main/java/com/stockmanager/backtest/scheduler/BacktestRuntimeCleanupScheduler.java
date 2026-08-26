package com.stockmanager.backtest.scheduler;

import com.stockmanager.backtest.service.BacktestRuntimeCleanupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** Disabled by default: enabling retention cleanup is an explicit operational choice. */
@Component
@ConditionalOnProperty(prefix = "app.backtest.cleanup", name = "enabled", havingValue = "true")
public class BacktestRuntimeCleanupScheduler {
    private static final Logger log = LoggerFactory.getLogger(BacktestRuntimeCleanupScheduler.class);
    private final BacktestRuntimeCleanupService cleanupService;
    private final int retentionDays;

    public BacktestRuntimeCleanupScheduler(BacktestRuntimeCleanupService cleanupService,
                                           @org.springframework.beans.factory.annotation.Value("${app.backtest.cleanup.retention-days:14}") int retentionDays) {
        this.cleanupService = cleanupService;
        this.retentionDays = Math.max(1, retentionDays);
    }

    @Scheduled(cron = "${app.backtest.cleanup.cron:0 30 3 * * *}", zone = "Asia/Shanghai")
    public void cleanupExpiredRuntimeFiles() {
        int deleted = cleanupService.cleanupCompletedBefore(LocalDateTime.now().minusDays(retentionDays));
        if (deleted > 0) log.info("已清理过期回测运行目录，count={}", deleted);
    }
}
