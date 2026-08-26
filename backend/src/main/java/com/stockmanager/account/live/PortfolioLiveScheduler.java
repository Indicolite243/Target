package com.stockmanager.account.live;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduler tasks are deliberately split: one QMT read path and one Redis-to-MySQL write path. */
@Component
@ConditionalOnProperty(prefix = "app.portfolio-live", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PortfolioLiveScheduler {
    private final PortfolioLiveService portfolioLiveService;

    public PortfolioLiveScheduler(PortfolioLiveService portfolioLiveService) {
        this.portfolioLiveService = portfolioLiveService;
    }

    @Scheduled(fixedDelayString = "${app.portfolio-live.collect-interval-ms:2000}",
            initialDelayString = "${app.portfolio-live.initial-delay-ms:3000}")
    public void collectLivePortfolios() {
        portfolioLiveService.collectAllQmtAccounts();
    }

    @Scheduled(fixedDelayString = "${app.portfolio-live.persist-interval-ms:30000}",
            initialDelayString = "${app.portfolio-live.persist-initial-delay-ms:15000}")
    public void persistCurrentPortfolios() {
        portfolioLiveService.persistAllCachedQmtAccounts();
    }
}
