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

/** 根据量化任务的结果类型路由到风险、归因或回测持久化结果。 */
@Service
public class QuantTaskResultService {
    private final QuantTaskService taskService;
    private final AttributionResultPersistenceService attributionResultService;
    private final RiskAssessmentPersistenceService riskAssessmentPersistenceService;
    private final BacktestRunPersistenceService backtestRunPersistenceService;
    private final ObjectMapper objectMapper;

    /** 注入任务归属校验与三类结果持久化服务。 */
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

    /**
     * 校验任务归属和成功状态后返回完整结果；未完成任务不能提前读取半成品。
     */
    public Map<String, Object> requireResult(Long userId, Long taskId) {
        // 同时用userId和taskId读取，防止通过猜测Snowflake ID访问其他用户的计算结果。
        QuantTask task = taskService.requireOwned(userId, taskId);
        // 只有成功终态才保证结果已经完整落库；PENDING/RUNNING/FAILED都不能读取半成品。
        if (!QuantTaskService.SUCCEEDED.equals(task.getStatus())) {
            throw new BusinessException(409801, "量化任务尚未成功完成", HttpStatus.CONFLICT);
        }
        // 归因结果以taskId为唯一关联键，因此直接按taskId读取完整JSON。
        if ("ATTRIBUTION".equals(task.getResultType())) {
            return attributionResultService.requireTaskResult(taskId);
        }
        // 风险记录有独立主键，任务成功时已把该主键写入resultId。
        if ("RISK_ASSESSMENT".equals(task.getResultType()) && task.getResultId() != null) {
            // 风险服务返回强类型View，统一转换成Map以保持本控制器三种结果的响应签名一致。
            return objectMapper.convertValue(riskAssessmentPersistenceService.requireView(task.getResultId()), Map.class);
        }
        // 回测完整收益曲线保存在backtest_run.result_json，并以taskId做唯一键。
        if ("BACKTEST".equals(task.getResultType())) {
            return backtestRunPersistenceService.requireTaskResult(taskId);
        }
        // SUCCEEDED却没有受支持的resultType说明任务与结果协议不一致，不能返回空对象掩盖数据问题。
        throw new BusinessException(404803, "该任务没有可读取的结果", HttpStatus.NOT_FOUND);
    }
}
