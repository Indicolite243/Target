package com.stockmanager.account.live;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 实时账户的两个定时入口。
 *
 * <pre>
 * QMT --2s--> Redis 热快照 --30s--> MySQL 持久快照
 * 页面 --------优先读 Redis；未命中时读 MySQL-------->
 * </pre>
 *
 * 两个任务故意拆开：采集任务允许访问 QMT，持久化任务只读 Redis，避免把慢的
 * QMT SDK 调用放进数据库写入链路，也避免多个页面请求重复打 QMT。
 */
@Component
@ConditionalOnProperty(prefix = "app.portfolio-live", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PortfolioLiveScheduler {
    private final PortfolioLiveService portfolioLiveService;

    /** 注入实时组合服务。 */
    public PortfolioLiveScheduler(PortfolioLiveService portfolioLiveService) {
        this.portfolioLiveService = portfolioLiveService;
    }

    /**
     * 按配置频率为全部启用账户采集 QMT 实时组合并更新 Redis。
     * 默认启动三秒后执行首轮，之后每轮结束两秒再执行下一轮。
     */
    @Scheduled(fixedDelayString = "${app.portfolio-live.collect-interval-ms:2000}",
            initialDelayString = "${app.portfolio-live.initial-delay-ms:3000}")
    public void collectLivePortfolios() {
        // fixedDelay 表示“本轮执行结束后再等待”，不会因为上一轮慢而并发堆积。
        portfolioLiveService.collectAllQmtAccounts();
    }

    /**
     * 按较低频率把 Redis 当前组合批量同步到 MySQL 当前表。
     * 默认每三十秒执行，控制数据库写放大，同时为 QMT 离线展示保留最近事实。
     */
    @Scheduled(fixedDelayString = "${app.portfolio-live.persist-interval-ms:30000}",
            initialDelayString = "${app.portfolio-live.persist-initial-delay-ms:15000}")
    public void persistCurrentPortfolios() {
        // 这里只把 Redis 已经采集好的快照落库；故障时不会再次调用 QMT。
        portfolioLiveService.persistAllCachedQmtAccounts();
    }
}
