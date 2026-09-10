package com.stockmanager.quanttask.service;

import com.stockmanager.analysis.entity.AttributionResult;
import com.stockmanager.analysis.service.AnalysisService;
import com.stockmanager.analysis.service.AttributionResultPersistenceService;
import com.stockmanager.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 归因分析任务 Worker：计算、持久化、状态收敛必须按顺序完成。 */
@Component
public class AttributionTaskWorker {
    private final QuantTaskService taskService;
    private final AnalysisService analysisService;
    private final AttributionResultPersistenceService resultPersistenceService;

    /** 注入任务状态机、归因计算和结果持久化服务。 */
    public AttributionTaskWorker(QuantTaskService taskService, AnalysisService analysisService,
                                 AttributionResultPersistenceService resultPersistenceService) {
        this.taskService = taskService;
        this.analysisService = analysisService;
        this.resultPersistenceService = resultPersistenceService;
    }

    /** 执行账户归因，持久化完整结果并更新任务摘要与终态。 */
    public void execute(Long taskId, Long userId, Long accountId, String dimension, String source,
                        String from, String to, String traceId) {
        // 分析任务与回测任务共用状态机：先 CAS 抢占，再计算，最后保存结果并回写任务。
        // 返回false说明任务已经被取消、抢占或不存在；继续计算会造成重复结果。
        if (!taskService.markRunning(taskId)) return;
        try {
            // AnalysisService负责读取指定账户和区间的数据，并按dimension生成完整归因结果。
            Map<String, Object> result = analysisService.attribution(userId, accountId, dimension, source, from, to, traceId);
            // 先把完整明细保存到attribution_result，再把结果主键挂到quant_task。
            AttributionResult persisted = resultPersistenceService.save(taskId, accountId, dimension, source, result);
            // 结果表保存成功后才宣告任务成功，避免出现“任务成功但没有结果”的孤儿状态。
            taskService.succeed(taskId, "ATTRIBUTION", persisted.getId(), summary(result));
        } catch (BusinessException ex) {
            // 保留业务层已经分类的错误码，例如账户不存在、历史数据不足或上游不可用。
            taskService.fail(taskId, String.valueOf(ex.getCode()), ex.getMessage());
        } catch (Exception ex) {
            // 所有异常都进入 FAILED，前端可以据此停止 loading 并展示可重试信息。
            taskService.fail(taskId, "ATTRIBUTION_TASK_FAILED", ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summary(Map<String, Object> result) {
        // 摘要供高频任务状态查询使用，不重复保存完整归因行。
        Map<String, Object> summary = new LinkedHashMap<>();
        // 保存实际参与计算的日期区间和数据来源，便于前端快速说明计算口径。
        summary.put("rangeStart", result.getOrDefault("range_start", ""));
        summary.put("rangeEnd", result.getOrDefault("range_end", ""));
        summary.put("source", result.getOrDefault("data_source", ""));
        // result.summary本身体积较小，可以直接进入任务摘要。
        Object detail = result.get("summary");
        if (detail instanceof Map<?, ?>) summary.put("summary", detail);
        // 大体量attributionRows只记录行数，完整内容由结果接口按需读取。
        Object rows = result.get("attributionRows");
        summary.put("rowCount", rows instanceof java.util.Collection<?> collection ? collection.size() : 0);
        return summary;
    }
}
