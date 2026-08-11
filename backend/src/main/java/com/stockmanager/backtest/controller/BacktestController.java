package com.stockmanager.backtest.controller;

import com.stockmanager.backtest.service.BacktestService;
import com.stockmanager.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/backtests")
public class BacktestController {
    private final BacktestService backtestService;

    public BacktestController(BacktestService backtestService) {
        this.backtestService = backtestService;
    }

    @PostMapping(value = "/run", consumes = "multipart/form-data")
    public ApiResponse<Map<String, Object>> run(
            @RequestParam("file") MultipartFile strategyFile,
            @RequestParam(value = "market_files", required = false) List<MultipartFile> marketFiles,
            @RequestParam("start_date") String startDate,
            @RequestParam("end_date") String endDate,
            @RequestParam(value = "engine_type", defaultValue = "auto") String engineType,
            @RequestParam(value = "benchmark_symbol", defaultValue = "") String benchmarkSymbol,
            @RequestParam(value = "enable_bear_protection", defaultValue = "false") boolean bearProtection,
            HttpServletRequest request) {
        String traceId = String.valueOf(request.getAttribute("traceId"));
        return ApiResponse.success(backtestService.run(strategyFile, marketFiles, startDate, endDate,
                engineType, benchmarkSymbol, bearProtection, traceId), traceId);
    }
}
