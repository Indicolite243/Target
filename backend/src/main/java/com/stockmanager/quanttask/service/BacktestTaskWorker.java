package com.stockmanager.quanttask.service;

import com.stockmanager.backtest.entity.BacktestRun;
import com.stockmanager.backtest.service.BacktestInputStorageService.BacktestInput;
import com.stockmanager.backtest.service.BacktestRunPersistenceService;
import com.stockmanager.common.exception.BusinessException;
import com.stockmanager.integration.quant.QuantClient;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/** 回测任务的异步执行边界：只负责调度量化服务和保存结果，不负责 HTTP 响应。 */
@Component
public class BacktestTaskWorker {
    private final QuantTaskService taskService;
    private final QuantClient quantClient;
    private final BacktestRunPersistenceService runPersistenceService;

    /** 注入任务状态机、FastAPI 客户端和回测结果持久化服务。 */
    public BacktestTaskWorker(QuantTaskService taskService, QuantClient quantClient,
                              BacktestRunPersistenceService runPersistenceService) {
        this.taskService = taskService;
        this.quantClient = quantClient;
        this.runPersistenceService = runPersistenceService;
    }

    /** 消费已暂存回测输入，执行 FastAPI 回测并写入结果和任务终态。 */
    public void execute(Long taskId, Long userId, BacktestInput input, String traceId) {
        /*
         * Worker 的生命周期：
         * PENDING --CAS--> RUNNING --FastAPI 完成--> 保存 BacktestRun --> SUCCEEDED
         *                                      \--异常----------------> FAILED
         * markRunning 返回 false 代表任务已被取消、已执行或不存在，不能重复消费。
         */
        // 条件更新失败说明任务可能已取消、已被其他 Worker 抢占或记录不存在；此时必须安静退出，不能重复执行策略。
        if (!taskService.markRunning(taskId)) return;
        try {
            // 量化进程只接收任务目录中的 Path，不接收 Web 请求对象或 MultipartFile。
            Map<String, Object> result = quantClient.runBacktest(input.strategyPath(), input.marketPaths(),
                    input.startDate().toString(), input.endDate().toString(), input.engineType(),
                    input.benchmarkSymbol(), input.bearProtection(), traceId);
            // FastAPI 成功只代表拿到了内存结果；先将完整报告写入 backtest_run，建立可长期查询的结果记录。
            BacktestRun run = runPersistenceService.save(taskId, userId, input.strategyFilename(), input.engineType(),
                    input.benchmarkSymbol(), input.startDate(), input.endDate(), input.runtimePath(),
                    readStrategySource(input), result);
            // 先保存完整结果，再把任务标记成功；这样成功任务一定能追溯到结果主键。
            taskService.succeed(taskId, "BACKTEST", run.getId(), summary(result));
        } catch (BusinessException ex) {
            // 可预期业务错误保留量化服务的错误码，便于前端显示和面试时定位责任边界。
            taskService.fail(taskId, String.valueOf(ex.getCode()), ex.getMessage());
        } catch (Exception ex) {
            // 未知异常也要收敛到 FAILED，不能让任务永久停留在 RUNNING。
            taskService.fail(taskId, "BACKTEST_TASK_FAILED", ex.getMessage());
        }
    }

    /** 源码副本用于后续解释；读取失败不能把已经成功完成的回测改判为失败。 */
    private String readStrategySource(BacktestInput input) {
        try { return Files.readString(input.strategyPath(), StandardCharsets.UTF_8); }
        catch (Exception ignored) { return null; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summary(Map<String, Object> result) {
        // quant_task 每秒都会被前端轮询，因此摘要只保留状态、文案、指标和交易日数量，不复制完整收益曲线。
        Map<String, Object> summary = new LinkedHashMap<>();
        // result 是 FastAPI data 外层，其中 status/message 描述本次策略运行结果。
        summary.put("status", result.getOrDefault("status", "unknown"));
        summary.put("message", result.getOrDefault("message", ""));
        // 真正的曲线、metrics 和 execution_meta 位于 result.data 中。
        Object data = result.get("data");
        if (data instanceof Map<?, ?> map) {
            // 指标体量较小，可直接放入任务摘要，列表页无需额外读取完整结果表。
            Object metrics = map.get("metrics");
            if (metrics instanceof Map<?, ?>) summary.put("metrics", metrics);
            // 不复制 dates 数组，只保存长度作为 tradingDays，避免任务表重复存储大数组。
            Object dates = map.get("dates");
            summary.put("tradingDays", dates instanceof java.util.Collection<?> collection ? collection.size() : 0);
        }
        return summary;
    }
}
