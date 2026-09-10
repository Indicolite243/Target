package com.stockmanager.quanttask.service;

import com.stockmanager.common.exception.BusinessException;
import com.stockmanager.risk.service.RiskAssessmentService;
import com.stockmanager.risk.service.RiskAssessmentPersistenceService;
import com.stockmanager.risk.vo.RiskAssessmentView;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 风险评估任务 Worker：把外部计算结果绑定到任务记录，保证结果可追溯。 */
@Component
public class RiskTaskWorker {
    private final QuantTaskService taskService;
    private final RiskAssessmentService riskAssessmentService;
    private final RiskAssessmentPersistenceService riskPersistenceService;

    /** 注入任务状态机和风险计算服务。 */
    public RiskTaskWorker(QuantTaskService taskService, RiskAssessmentService riskAssessmentService,
                          RiskAssessmentPersistenceService riskPersistenceService) {
        this.taskService = taskService;
        this.riskAssessmentService = riskAssessmentService;
        this.riskPersistenceService = riskPersistenceService;
    }

    /** 抢占任务、执行风险计算、关联结果并写入成功或失败终态。 */
    public void execute(Long taskId, Long userId, Long accountId, int days, String traceId) {
        // 风险评估也是一次性异步任务：数据库 CAS 保证同一 task 不会被两个 Worker 重复执行。
        // CAS失败时不抛异常，因为取消或其他Worker抢占都属于合法并发结果。
        if (!taskService.markRunning(taskId)) return;
        try {
            // calculate负责准备收益序列、调用量化计算并先生成独立风险评估记录。
            RiskAssessmentView result = riskAssessmentService.calculate(userId, accountId, days, traceId);
            // 风险结果先绑定 taskId，再把风险记录主键写入任务，形成可追溯关系。
            riskPersistenceService.attachTask(result.id(), taskId);
            // quant_task只保存前端轮询需要的小型摘要，完整指标仍从风险结果表读取。
            taskService.succeed(taskId, "RISK_ASSESSMENT", result.id(), Map.of(
                    "riskLevel", result.riskLevel(), "riskScore", result.riskScore(),
                    "dataVersion", result.dataVersion()));
        } catch (BusinessException ex) {
            // 已知业务异常保留原错误码，便于前端区分参数、数据和上游错误。
            taskService.fail(taskId, String.valueOf(ex.getCode()), ex.getMessage());
        } catch (Exception ex) {
            // 不吞掉异常：必须让任务从 RUNNING 进入 FAILED，避免状态永久悬挂。
            taskService.fail(taskId, "RISK_TASK_FAILED", ex.getMessage());
        }
    }
}
