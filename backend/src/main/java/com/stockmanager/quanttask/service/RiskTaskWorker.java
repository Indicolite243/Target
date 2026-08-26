package com.stockmanager.quanttask.service;

import com.stockmanager.common.exception.BusinessException;
import com.stockmanager.risk.service.RiskAssessmentService;
import com.stockmanager.risk.service.RiskAssessmentPersistenceService;
import com.stockmanager.risk.vo.RiskAssessmentView;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class RiskTaskWorker {
    private final QuantTaskService taskService;
    private final RiskAssessmentService riskAssessmentService;
    private final RiskAssessmentPersistenceService riskPersistenceService;

    public RiskTaskWorker(QuantTaskService taskService, RiskAssessmentService riskAssessmentService,
                          RiskAssessmentPersistenceService riskPersistenceService) {
        this.taskService = taskService;
        this.riskAssessmentService = riskAssessmentService;
        this.riskPersistenceService = riskPersistenceService;
    }

    public void execute(Long taskId, Long userId, Long accountId, int days, String traceId) {
        if (!taskService.markRunning(taskId)) return;
        try {
            RiskAssessmentView result = riskAssessmentService.calculate(userId, accountId, days, traceId);
            riskPersistenceService.attachTask(result.id(), taskId);
            taskService.succeed(taskId, "RISK_ASSESSMENT", result.id(), Map.of(
                    "riskLevel", result.riskLevel(), "riskScore", result.riskScore(),
                    "dataVersion", result.dataVersion()));
        } catch (BusinessException ex) {
            taskService.fail(taskId, String.valueOf(ex.getCode()), ex.getMessage());
        } catch (Exception ex) {
            taskService.fail(taskId, "RISK_TASK_FAILED", ex.getMessage());
        }
    }
}
