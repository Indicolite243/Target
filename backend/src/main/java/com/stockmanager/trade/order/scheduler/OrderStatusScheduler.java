package com.stockmanager.trade.order.scheduler;

import com.stockmanager.trade.order.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

/** Polls only unfinished QMT orders; list APIs remain local MySQL reads. */
@Component
@ConditionalOnProperty(prefix = "app.order-status", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderStatusScheduler {
    private static final Logger log = LoggerFactory.getLogger(OrderStatusScheduler.class);
    private final OrderService orderService;
    private final ReentrantLock pollLock = new ReentrantLock();

    public OrderStatusScheduler(OrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(fixedDelayString = "${app.order-status.poll-interval-ms:3000}",
            initialDelayString = "${app.order-status.initial-delay-ms:10000}")
    public void refreshOpenOrderStatuses() {
        if (!pollLock.tryLock()) return;
        try {
            int changed = orderService.refreshOpenOrderStatuses();
            if (changed > 0) log.info("QMT订单状态已收敛，changed={}", changed);
        } catch (Exception ex) {
            log.warn("QMT订单状态轮询失败，保留MySQL最近确认状态，reason={}", ex.getMessage());
        } finally {
            pollLock.unlock();
        }
    }
}
