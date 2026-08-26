package com.stockmanager.risk.controller;

import com.stockmanager.common.response.ApiResponse;
import com.stockmanager.quanttask.entity.QuantTask;
import com.stockmanager.quanttask.service.QuantTaskService;
import com.stockmanager.quanttask.service.RiskTaskWorker;
import com.stockmanager.quanttask.vo.QuantTaskView;
import com.stockmanager.risk.service.RiskAssessmentService;
import com.stockmanager.risk.vo.RiskAssessmentView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}/risk-assessments")
public class RiskAssessmentController {
    private final RiskAssessmentService service;
    private final QuantTaskService taskService;
    private final RiskTaskWorker riskTaskWorker;
    private final ThreadPoolTaskExecutor quantTaskExecutor;

    public RiskAssessmentController(RiskAssessmentService service, QuantTaskService taskService,
                                    RiskTaskWorker riskTaskWorker,
                                    @Qualifier("quantTaskExecutor") ThreadPoolTaskExecutor quantTaskExecutor) {
        this.service = service;
        this.taskService = taskService;
        this.riskTaskWorker = riskTaskWorker;
        this.quantTaskExecutor = quantTaskExecutor;
    }

    @GetMapping("/latest")
    public ApiResponse<RiskAssessmentView> latest(@PathVariable Long accountId,
                                              @RequestParam(defaultValue = "250") int days,
                                              @RequestParam(required = false) String startDate,
                                              @RequestParam(required = false) String endDate,
                                              @RequestParam(defaultValue = "DAILY") String granularity,
                                              Authentication authentication, HttpServletRequest request) {
        return ApiResponse.success(service.latestOrCalculate(userId(authentication), accountId, days,
                startDate, endDate, granularity.toUpperCase(), traceId(request)), traceId(request));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<QuantTaskView>> calculate(@PathVariable Long accountId,
                                                                 @RequestBody(required = false) MapRequest body,
                                                                 @RequestHeader(value = "X-Idempotency-Key", required = false)
                                                                 String idempotencyKey,
                                                                 Authentication authentication,
                                                                 HttpServletRequest request) {
        int days = body == null || body.days() == null ? 250 : body.days();
        String traceId = traceId(request);
        Long userId = userId(authentication);
        QuantTask task = taskService.create(userId, accountId, "RISK", Map.of("days", days), idempotencyKey);
        if (QuantTaskService.PENDING.equals(task.getStatus())) {
            try {
                quantTaskExecutor.execute(() -> riskTaskWorker.execute(task.getId(), userId, accountId, days, traceId));
            } catch (RejectedExecutionException ex) {
                taskService.reject(task.getId());
            }
        }
        QuantTaskView view = taskService.view(taskService.requireOwned(userId, task.getId()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success("风险评估任务已受理", view, traceId));
    }

    private Long userId(Authentication authentication) { return Long.valueOf(authentication.getName()); }
    private String traceId(HttpServletRequest request) { return String.valueOf(request.getAttribute("traceId")); }
    public record MapRequest(Integer days) {}
}
