package com.stockmanager.backtest.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmanager.backtest.entity.BacktestRun;
import com.stockmanager.backtest.mapper.BacktestRunMapper;
import com.stockmanager.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

@Service
public class BacktestRunPersistenceService {
    private final BacktestRunMapper mapper;
    private final ObjectMapper objectMapper;

    public BacktestRunPersistenceService(BacktestRunMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public BacktestRun save(Long taskId, Long userId, String strategyFilename, String engineType,
                            String benchmarkSymbol, LocalDate startDate, LocalDate endDate,
                            String runtimePath, Map<String, Object> result) {
        BacktestRun existing = mapper.selectOne(Wrappers.<BacktestRun>lambdaQuery().eq(BacktestRun::getTaskId, taskId));
        if (existing != null) return existing;
        LocalDateTime now = LocalDateTime.now();
        BacktestRun run = new BacktestRun();
        run.setTaskId(taskId);
        run.setUserId(userId);
        run.setStrategyFilename(strategyFilename);
        run.setEngineType(engineType);
        run.setBenchmarkSymbol(benchmarkSymbol);
        run.setStartDate(startDate);
        run.setEndDate(endDate);
        run.setRuntimePath(runtimePath);
        run.setResultJson(json(result));
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        mapper.insert(run);
        return run;
    }

    public Map<String, Object> requireTaskResult(Long taskId) {
        BacktestRun run = mapper.selectOne(Wrappers.<BacktestRun>lambdaQuery().eq(BacktestRun::getTaskId, taskId));
        if (run == null) throw new BusinessException(404702, "回测结果不存在", HttpStatus.NOT_FOUND);
        try { return objectMapper.readValue(run.getResultJson(), new TypeReference<>() {}); }
        catch (Exception ex) { throw new BusinessException(500702, "回测结果数据损坏", HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    private String json(Map<String, Object> value) {
        try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception ex) { throw new BusinessException(500702, "回测结果序列化失败", HttpStatus.INTERNAL_SERVER_ERROR); }
    }
}
