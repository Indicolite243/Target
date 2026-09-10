package com.stockmanager.backtest.scheduler;

import com.stockmanager.backtest.service.BacktestRuntimeCleanupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 回测运行目录保留策略调度器。
 * 默认关闭，只有明确打开 app.backtest.cleanup.enabled 才会删除任务目录，防止
 * 面试演示或审计期间的原始输入/报告被意外清理。
 */
@Component
@ConditionalOnProperty(prefix = "app.backtest.cleanup", name = "enabled", havingValue = "true")
public class BacktestRuntimeCleanupScheduler {
    private static final Logger log = LoggerFactory.getLogger(BacktestRuntimeCleanupScheduler.class);
    private final BacktestRuntimeCleanupService cleanupService;
    private final int retentionDays;

    /** 注入清理服务和保留天数配置。 */
    public BacktestRuntimeCleanupScheduler(BacktestRuntimeCleanupService cleanupService,
                                           @org.springframework.beans.factory.annotation.Value("${app.backtest.cleanup.retention-days:14}") int retentionDays) {
        this.cleanupService = cleanupService;
        this.retentionDays = Math.max(1, retentionDays);
    }

    @Scheduled(cron = "${app.backtest.cleanup.cron:0 30 3 * * *}", zone = "Asia/Shanghai")
    /** 按 Cron 清理超过保留期的终态回测运行目录。 */
    public void cleanupExpiredRuntimeFiles() {
        // 只删除超过保留天数的终态任务目录，数据库结果不受影响。
        int deleted = cleanupService.cleanupCompletedBefore(LocalDateTime.now().minusDays(retentionDays));
        if (deleted > 0) log.info("已清理过期回测运行目录，count={}", deleted);
    }
}
