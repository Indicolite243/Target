package com.stockmanager.quanttask.service;

import com.stockmanager.analysis.entity.AttributionResult;
import com.stockmanager.analysis.service.AnalysisService;
import com.stockmanager.analysis.service.AttributionResultPersistenceService;
import com.stockmanager.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AttributionTaskWorker {
    private final QuantTaskService taskService;
    private final AnalysisService analysisService;
    private final AttributionResultPersistenceService resultPersistenceService;

    public AttributionTaskWorker(QuantTaskService taskService, AnalysisService analysisService,
                                 AttributionResultPersistenceService resultPersistenceService) {
        this.taskService = taskService;
        this.analysisService = analysisService;
        this.resultPersistenceService = resultPersistenceService;
    }

    public void execute(Long taskId, Long userId, Long accountId, String dimension, String source,
                        String from, String to, String traceId) {
        if (!taskService.markRunning(taskId)) return;
        try {
            Map<String, Object> result = analysisService.attribution(userId, accountId, dimension, source, from, to, traceId);
            AttributionResult persisted = resultPersistenceService.save(taskId, accountId, dimension, source, result);
            taskService.succeed(taskId, "ATTRIBUTION", persisted.getId(), summary(result));
        } catch (BusinessException ex) {
            taskService.fail(taskId, String.valueOf(ex.getCode()), ex.getMessage());
        } catch (Exception ex) {
            taskService.fail(taskId, "ATTRIBUTION_TASK_FAILED", ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summary(Map<String, Object> result) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("rangeStart", result.getOrDefault("range_start", ""));
        summary.put("rangeEnd", result.getOrDefault("range_end", ""));
        summary.put("source", result.getOrDefault("data_source", ""));
        Object detail = result.get("summary");
        if (detail instanceof Map<?, ?>) summary.put("summary", detail);
        Object rows = result.get("attributionRows");
        summary.put("rowCount", rows instanceof java.util.Collection<?> collection ? collection.size() : 0);
        return summary;
    }
}
