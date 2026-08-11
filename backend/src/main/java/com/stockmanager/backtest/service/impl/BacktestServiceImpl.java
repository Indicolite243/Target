package com.stockmanager.backtest.service.impl;

import com.stockmanager.backtest.service.BacktestService;
import com.stockmanager.common.exception.BusinessException;
import com.stockmanager.integration.quant.QuantClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Service
public class BacktestServiceImpl implements BacktestService {
    private final QuantClient quantClient;

    public BacktestServiceImpl(QuantClient quantClient) {
        this.quantClient = quantClient;
    }

    @Override
    public Map<String, Object> run(MultipartFile strategyFile, List<MultipartFile> marketFiles,
                                   String startDate, String endDate, String engineType,
                                   String benchmarkSymbol, boolean bearProtection, String traceId) {
        if (strategyFile == null || strategyFile.isEmpty()) {
            throw new BusinessException(400701, "请选择 Python 策略文件", HttpStatus.BAD_REQUEST);
        }
        String filename = strategyFile.getOriginalFilename() == null ? "" : strategyFile.getOriginalFilename();
        if (!filename.toLowerCase().endsWith(".py")) {
            throw new BusinessException(400701, "策略文件必须是 .py 文件", HttpStatus.BAD_REQUEST);
        }
        return quantClient.runBacktest(strategyFile, marketFiles == null ? List.of() : marketFiles,
                startDate, endDate, engineType, benchmarkSymbol, bearProtection, traceId);
    }
}
