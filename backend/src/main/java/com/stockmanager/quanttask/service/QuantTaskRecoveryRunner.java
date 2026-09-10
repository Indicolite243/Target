package com.stockmanager.quanttask.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 应用重启后的量化任务状态恢复器。
 *
 * <p>任务状态保存在MySQL，但Runnable队列只存在JVM内存。应用重启后无法确认旧任务是否
 * 已经产生外部副作用，因此不盲目重放，而是把遗留PENDING/RUNNING任务明确标记为失败。</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.quant-task.recovery", name = "enabled", havingValue = "true", matchIfMissing = true)
public class QuantTaskRecoveryRunner {
    private static final Logger log = LoggerFactory.getLogger(QuantTaskRecoveryRunner.class);
    private final QuantTaskService taskService;

    /** 注入量化任务状态机。 */
    public QuantTaskRecoveryRunner(QuantTaskService taskService) {
        this.taskService = taskService;
    }

    @EventListener(ApplicationReadyEvent.class)
    /** 应用启动完成后将上次进程遗留任务统一标记为中断失败。 */
    public void markInterruptedTasks() {
        // 一次条件更新收敛全部遗留任务，返回值用于运维日志而不是控制业务分支。
        int count = taskService.failInterruptedAfterRestart();
        // 没有遗留任务时不输出噪声；有任务时记录数量，方便排查异常重启影响范围。
        if (count > 0) log.warn("应用重启后已标记中断量化任务，count={}", count);
    }
}
