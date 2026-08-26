package com.stockmanager.analysis.controller;

import com.stockmanager.analysis.service.AnalysisService;
import com.stockmanager.common.response.ApiResponse;
import com.stockmanager.quanttask.entity.QuantTask;
import com.stockmanager.quanttask.service.AttributionTaskWorker;
import com.stockmanager.quanttask.service.QuantTaskService;
import com.stockmanager.quanttask.vo.QuantTaskView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}")
public class AnalysisController {
    private final AnalysisService analysisService;
    private final QuantTaskService taskService;
    private final AttributionTaskWorker attributionTaskWorker;
    private final ThreadPoolTaskExecutor quantTaskExecutor;

    public AnalysisController(AnalysisService analysisService, QuantTaskService taskService,
                              AttributionTaskWorker attributionTaskWorker,
                              @Qualifier("quantTaskExecutor") ThreadPoolTaskExecutor quantTaskExecutor) {
        this.analysisService = analysisService;
        this.taskService = taskService;
        this.attributionTaskWorker = attributionTaskWorker;
        this.quantTaskExecutor = quantTaskExecutor;
    }

    @GetMapping({"/allocations", "/analyses/allocation"})
    public ApiResponse<Map<String, Object>> allocation(@PathVariable Long accountId,
                                                       @RequestParam(defaultValue = "ASSET_CLASS") String dimension,
                                                       @RequestParam(defaultValue = "qmt") String source,
                                                       Authentication authentication, HttpServletRequest request) {
        return ApiResponse.success(analysisService.allocation(userId(authentication), accountId,
                dimension.toUpperCase(), source.toLowerCase()), traceId(request));
    }

    @GetMapping("/analyses/period-comparison")
    public ApiResponse<Map<String, Object>> period(@PathVariable Long accountId,
                                                   @RequestParam(defaultValue = "YEAR") String periodType,
                                                   @RequestParam(required = false) String from,
                                                   @RequestParam(required = false) String to,
                                                   @RequestParam(defaultValue = "DAILY") String granularity,
                                                   @RequestParam(defaultValue = "SNAPSHOT") String calculationMode,
                                                   Authentication authentication, HttpServletRequest request) {
        return ApiResponse.success(analysisService.periodComparison(userId(authentication), accountId,
                periodType.toUpperCase(), from, to, granularity.toUpperCase(),
                calculationMode.toUpperCase(), traceId(request)), traceId(request));
    }

    @GetMapping("/analyses/attribution")
    public ApiResponse<Map<String, Object>> attribution(@PathVariable Long accountId,
                                                        @RequestParam(defaultValue = "ASSET") String dimension,
                                                        @RequestParam(defaultValue = "qmt") String source,
                                                        @RequestParam(required = false) String from,
                                                        @RequestParam(required = false) String to,
                                                        Authentication authentication, HttpServletRequest request) {
        return ApiResponse.success(analysisService.attribution(userId(authentication), accountId,
                dimension.toUpperCase(), source.toLowerCase(), from, to, traceId(request)), traceId(request));
    }

    @PostMapping("/analyses/attribution/tasks")
    public ResponseEntity<ApiResponse<QuantTaskView>> submitAttribution(
            @PathVariable Long accountId, @RequestBody(required = false) AttributionTaskRequest body,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication, HttpServletRequest request) {
        String dimension = body == null || body.dimension() == null ? "ASSET" : body.dimension().toUpperCase();
        String source = body == null || body.source() == null ? "MYSQL" : body.source().toUpperCase();
        String from = body == null ? null : body.from();
        String to = body == null ? null : body.to();
        Long userId = userId(authentication);
        String traceId = traceId(request);
        QuantTask task = taskService.create(userId, accountId, "ATTRIBUTION",
                Map.of("dimension", dimension, "source", source, "from", from == null ? "" : from,
                        "to", to == null ? "" : to), idempotencyKey);
        if (QuantTaskService.PENDING.equals(task.getStatus())) {
            try {
                quantTaskExecutor.execute(() -> attributionTaskWorker.execute(task.getId(), userId, accountId,
                        dimension, source, from, to, traceId));
            } catch (RejectedExecutionException ex) {
                taskService.reject(task.getId());
            }
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success("归因分析任务已受理",
                taskService.view(taskService.requireOwned(userId, task.getId())), traceId));
    }

    private Long userId(Authentication authentication) { return Long.valueOf(authentication.getName()); }
    private String traceId(HttpServletRequest request) { return String.valueOf(request.getAttribute("traceId")); }
    public record AttributionTaskRequest(String dimension, String source, String from, String to) {}
}
