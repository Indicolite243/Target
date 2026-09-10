package com.stockmanager.trade.order.scheduler;

import com.stockmanager.trade.order.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 订单状态收敛器：只轮询未结束订单，列表查询仍然只读 MySQL。
 *
 * <pre>
 * PENDING/CANCEL_PENDING --每3秒--> QMT 查询 --有变化--> 短事务更新 MySQL
 *                                             \--失败--> 保留最近确认状态，下轮重试
 * </pre>
 */
@Component
@ConditionalOnProperty(prefix = "app.order-status", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderStatusScheduler {
    /** 日志对象，用于记录本轮收敛数量和异常原因，不记录Token/账号敏感信息。 */
    private static final Logger log = LoggerFactory.getLogger(OrderStatusScheduler.class);
    /** 订单应用服务；调度器不直接访问Mapper或QMT。 */
    private final OrderService orderService;
    /** 防止上一轮外部查询尚未结束时，下一轮再次并发访问QMT。 */
    private final ReentrantLock pollLock = new ReentrantLock();

    /** 注入订单应用服务。 */
    public OrderStatusScheduler(OrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(fixedDelayString = "${app.order-status.poll-interval-ms:3000}",
            initialDelayString = "${app.order-status.initial-delay-ms:10000}")
    /**
     * 定期批量查询QMT未终态订单，并将变化收敛到MySQL。
     *
     * <p>fixedDelay表示上一轮执行结束后再等待下一轮，避免QMT慢查询时任务堆积。</p>
     */
    public void refreshOpenOrderStatuses() {
        // tryLock 防止上一轮 QMT 查询较慢时，下一轮再次进入造成 SDK 并发调用。
        if (!pollLock.tryLock()) {
            // 当前轮次直接跳过；下一次fixedDelay仍会继续尝试，不阻塞调度线程。
            return;
        }
        try {
            // Service内部一次批量查询所有账户的可跟踪订单，并只更新实际发生变化的记录。
            int changed = orderService.refreshOpenOrderStatuses();
            // changed=0是正常情况，不打印高频info，减少轮询日志和IO开销。
            if (changed > 0) log.info("QMT订单状态已收敛，changed={}", changed);
        } catch (Exception ex) {
            // 单轮失败不影响应用和下一轮任务；Service已经保证保留MySQL最近确认状态。
            log.warn("QMT订单状态轮询失败，保留MySQL最近确认状态，reason={}", ex.getMessage());
        } finally {
            // 无论Service成功还是异常，都必须释放锁，否则后续轮询将永久跳过。
            pollLock.unlock();
        }
    }
}
