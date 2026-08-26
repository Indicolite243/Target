package com.stockmanager.backtest.service;

import com.stockmanager.backtest.service.BacktestInputStorageService.BacktestInput;
import com.stockmanager.common.exception.BusinessException;
import com.stockmanager.quanttask.entity.QuantTask;
import com.stockmanager.quanttask.service.BacktestTaskWorker;
import com.stockmanager.quanttask.service.QuantTaskService;
import com.stockmanager.quanttask.vo.QuantTaskView;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

@Service
public class BacktestTaskSubmissionService {
    private final QuantTaskService taskService;
    private final BacktestInputStorageService inputStorageService;
    private final BacktestTaskWorker backtestTaskWorker;
    private final ThreadPoolTaskExecutor quantTaskExecutor;

    public BacktestTaskSubmissionService(QuantTaskService taskService, BacktestInputStorageService inputStorageService,
                                         BacktestTaskWorker backtestTaskWorker,
                                         @Qualifier("quantTaskExecutor") ThreadPoolTaskExecutor quantTaskExecutor) {
        this.taskService = taskService;
        this.inputStorageService = inputStorageService;
        this.backtestTaskWorker = backtestTaskWorker;
        this.quantTaskExecutor = quantTaskExecutor;
    }

    public QuantTaskView submit(Long userId, MultipartFile strategyFile, List<MultipartFile> marketFiles,
                                String startDate, String endDate, String engineType, String benchmarkSymbol,
                                boolean bearProtection, String idempotencyKey, String traceId) {
        LocalDate start = parseDate(startDate, "开始日期格式错误");
        LocalDate end = parseDate(endDate, "结束日期格式错误");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("strategyFilename", strategyFile == null ? "" : strategyFile.getOriginalFilename());
        request.put("marketFileCount", marketFiles == null ? 0 : marketFiles.size());
        request.put("startDate", startDate);
        request.put("endDate", endDate);
        request.put("engineType", engineType == null ? "auto" : engineType);
        request.put("benchmarkSymbol", benchmarkSymbol == null ? "" : benchmarkSymbol);
        request.put("bearProtection", bearProtection);
        QuantTask task = taskService.create(userId, null, "BACKTEST", request, idempotencyKey);
        if (QuantTaskService.PENDING.equals(task.getStatus())) {
            try {
                BacktestInput input = inputStorageService.stage(task.getId(), strategyFile, marketFiles, start, end,
                        engineType, benchmarkSymbol, bearProtection);
                quantTaskExecutor.execute(() -> backtestTaskWorker.execute(task.getId(), userId, input, traceId));
            } catch (BusinessException ex) {
                taskService.fail(task.getId(), String.valueOf(ex.getCode()), ex.getMessage());
                throw ex;
            } catch (RejectedExecutionException ex) {
                taskService.reject(task.getId());
            }
        }
        return taskService.view(taskService.requireOwned(userId, task.getId()));
    }

    private LocalDate parseDate(String value, String message) {
        try { return LocalDate.parse(value); }
        catch (Exception ex) { throw new BusinessException(400701, message, HttpStatus.BAD_REQUEST); }
    }
}
