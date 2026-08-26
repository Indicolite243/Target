package com.stockmanager.quanttask.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmanager.analysis.service.AttributionResultPersistenceService;
import com.stockmanager.backtest.service.BacktestRunPersistenceService;
import com.stockmanager.common.exception.BusinessException;
import com.stockmanager.quanttask.entity.QuantTask;
import com.stockmanager.risk.service.RiskAssessmentPersistenceService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class QuantTaskResultService {
    private final QuantTaskService taskService;
    private final AttributionResultPersistenceService attributionResultService;
    private final RiskAssessmentPersistenceService riskAssessmentPersistenceService;
    private final BacktestRunPersistenceService backtestRunPersistenceService;
    private final ObjectMapper objectMapper;

    public QuantTaskResultService(QuantTaskService taskService,
                                  AttributionResultPersistenceService attributionResultService,
                                  RiskAssessmentPersistenceService riskAssessmentPersistenceService,
                                  BacktestRunPersistenceService backtestRunPersistenceService,
                                  ObjectMapper objectMapper) {
        this.taskService = taskService;
        this.attributionResultService = attributionResultService;
        this.riskAssessmentPersistenceService = riskAssessmentPersistenceService;
        this.backtestRunPersistenceService = backtestRunPersistenceService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> requireResult(Long userId, Long taskId) {
        QuantTask task = taskService.requireOwned(userId, taskId);
        if (!QuantTaskService.SUCCEEDED.equals(task.getStatus())) {
            throw new BusinessException(409801, "量化任务尚未成功完成", HttpStatus.CONFLICT);
        }
        if ("ATTRIBUTION".equals(task.getResultType())) {
            return attributionResultService.requireTaskResult(taskId);
        }
        if ("RISK_ASSESSMENT".equals(task.getResultType()) && task.getResultId() != null) {
            return objectMapper.convertValue(riskAssessmentPersistenceService.requireView(task.getResultId()), Map.class);
        }
        if ("BACKTEST".equals(task.getResultType())) {
            return backtestRunPersistenceService.requireTaskResult(taskId);
        }
        throw new BusinessException(404803, "该任务没有可读取的结果", HttpStatus.NOT_FOUND);
    }
}
