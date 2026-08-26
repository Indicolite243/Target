package com.stockmanager.backtest.controller;

import com.stockmanager.backtest.service.BacktestTaskSubmissionService;
import com.stockmanager.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import com.stockmanager.quanttask.vo.QuantTaskView;

@RestController
@RequestMapping("/api/v1/backtests")
public class BacktestController {
    private final BacktestTaskSubmissionService taskSubmissionService;

    public BacktestController(BacktestTaskSubmissionService taskSubmissionService) {
        this.taskSubmissionService = taskSubmissionService;
    }

    @PostMapping(value = "/run", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<QuantTaskView>> run(
            @RequestParam("file") MultipartFile strategyFile,
            @RequestParam(value = "market_files", required = false) List<MultipartFile> marketFiles,
            @RequestParam("start_date") String startDate,
            @RequestParam("end_date") String endDate,
            @RequestParam(value = "engine_type", defaultValue = "auto") String engineType,
            @RequestParam(value = "benchmark_symbol", defaultValue = "") String benchmarkSymbol,
            @RequestParam(value = "enable_bear_protection", defaultValue = "false") boolean bearProtection,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        String traceId = String.valueOf(request.getAttribute("traceId"));
        QuantTaskView task = taskSubmissionService.submit(Long.valueOf(authentication.getName()), strategyFile,
                marketFiles, startDate, endDate, engineType, benchmarkSymbol, bearProtection, idempotencyKey, traceId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success("回测任务已受理", task, traceId));
    }
}
