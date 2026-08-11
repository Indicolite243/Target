package com.stockmanager.backtest.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

public interface BacktestService {
    Map<String, Object> run(MultipartFile strategyFile, List<MultipartFile> marketFiles,
                            String startDate, String endDate, String engineType,
                            String benchmarkSymbol, boolean bearProtection, String traceId);
}
