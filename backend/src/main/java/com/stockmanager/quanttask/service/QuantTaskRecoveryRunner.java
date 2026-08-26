package com.stockmanager.quanttask.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Makes interrupted in-memory jobs visible and retryable after a process restart. */
@Component
@ConditionalOnProperty(prefix = "app.quant-task.recovery", name = "enabled", havingValue = "true", matchIfMissing = true)
public class QuantTaskRecoveryRunner {
    private static final Logger log = LoggerFactory.getLogger(QuantTaskRecoveryRunner.class);
    private final QuantTaskService taskService;

    public QuantTaskRecoveryRunner(QuantTaskService taskService) {
        this.taskService = taskService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void markInterruptedTasks() {
        int count = taskService.failInterruptedAfterRestart();
        if (count > 0) log.warn("应用重启后已标记中断量化任务，count={}", count);
    }
}
