package com.stockmanager.quanttask.service;

import com.stockmanager.backtest.entity.BacktestRun;
import com.stockmanager.backtest.service.BacktestInputStorageService.BacktestInput;
import com.stockmanager.backtest.service.BacktestRunPersistenceService;
import com.stockmanager.common.exception.BusinessException;
import com.stockmanager.integration.quant.QuantClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class BacktestTaskWorker {
    private final QuantTaskService taskService;
    private final QuantClient quantClient;
    private final BacktestRunPersistenceService runPersistenceService;

    public BacktestTaskWorker(QuantTaskService taskService, QuantClient quantClient,
                              BacktestRunPersistenceService runPersistenceService) {
        this.taskService = taskService;
        this.quantClient = quantClient;
        this.runPersistenceService = runPersistenceService;
    }

    public void execute(Long taskId, Long userId, BacktestInput input, String traceId) {
        if (!taskService.markRunning(taskId)) return;
        try {
            Map<String, Object> result = quantClient.runBacktest(input.strategyPath(), input.marketPaths(),
                    input.startDate().toString(), input.endDate().toString(), input.engineType(),
                    input.benchmarkSymbol(), input.bearProtection(), traceId);
            BacktestRun run = runPersistenceService.save(taskId, userId, input.strategyFilename(), input.engineType(),
                    input.benchmarkSymbol(), input.startDate(), input.endDate(), input.runtimePath(), result);
            taskService.succeed(taskId, "BACKTEST", run.getId(), summary(result));
        } catch (BusinessException ex) {
            taskService.fail(taskId, String.valueOf(ex.getCode()), ex.getMessage());
        } catch (Exception ex) {
            taskService.fail(taskId, "BACKTEST_TASK_FAILED", ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summary(Map<String, Object> result) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", result.getOrDefault("status", "unknown"));
        summary.put("message", result.getOrDefault("message", ""));
        Object data = result.get("data");
        if (data instanceof Map<?, ?> map) {
            Object metrics = map.get("metrics");
            if (metrics instanceof Map<?, ?>) summary.put("metrics", metrics);
            Object dates = map.get("dates");
            summary.put("tradingDays", dates instanceof java.util.Collection<?> collection ? collection.size() : 0);
        }
        return summary;
    }
}
