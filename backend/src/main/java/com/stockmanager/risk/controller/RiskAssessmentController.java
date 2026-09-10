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

/**
 * 风险评估查询和异步任务受理接口。
 *
 * <p>latest 接口保留同步兼容行为；POST 接口创建可轮询任务并提交独立有界线程池。</p>
 */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/risk-assessments")
public class RiskAssessmentController {
    private final RiskAssessmentService service;
    private final QuantTaskService taskService;
    private final RiskTaskWorker riskTaskWorker;
    private final ThreadPoolTaskExecutor quantTaskExecutor;

    /** 注入风险服务、任务状态机、风险 Worker 和量化线程池。 */
    public RiskAssessmentController(RiskAssessmentService service, QuantTaskService taskService,
                                    RiskTaskWorker riskTaskWorker,
                                    @Qualifier("quantTaskExecutor") ThreadPoolTaskExecutor quantTaskExecutor) {
        this.service = service;
        this.taskService = taskService;
        this.riskTaskWorker = riskTaskWorker;
        this.quantTaskExecutor = quantTaskExecutor;
    }

    /** 按日期范围和粒度读取或计算最近风险结果。 */
    @GetMapping("/latest")
    public ApiResponse<RiskAssessmentView> latest(
            @PathVariable Long accountId,
            // 未指定日期时默认向前取250个自然日；Service仍要求至少两个有效快照点。
            @RequestParam(defaultValue = "250") int days,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            // DAILY按日抽样，ALL使用区间内全部盘中快照。
            @RequestParam(defaultValue = "DAILY") String granularity,
            Authentication authentication, HttpServletRequest request) {
        // 兼容接口同步完成“历史读取→FastAPI计算→MySQL保存”，适用于当前前端直接刷新。
        return ApiResponse.success(service.latestOrCalculate(userId(authentication), accountId, days,
                startDate, endDate, granularity.toUpperCase(), traceId(request)), traceId(request));
    }

    /** 创建风险评估任务，立即返回 202 和任务 ID。 */
    @PostMapping
    public ResponseEntity<ApiResponse<QuantTaskView>> calculate(@PathVariable Long accountId,
                                                                 @RequestBody(required = false) MapRequest body,
                                                                 @RequestHeader(value = "X-Idempotency-Key", required = false)
                                                                 String idempotencyKey,
                                                                 Authentication authentication,
                                                                 HttpServletRequest request) {
        // 异步接口当前只接收回看天数；日期范围版仍由latest兼容接口提供。
        int days = body == null || body.days() == null ? 250 : body.days();
        String traceId = traceId(request);
        Long userId = userId(authentication);
        // userId+任务类型+幂等键可以复用同一逻辑提交，防止网络重试重复计算。
        QuantTask task = taskService.create(userId, accountId, "RISK", Map.of("days", days), idempotencyKey);
        if (QuantTaskService.PENDING.equals(task.getStatus())) {
            try {
                // Worker在独立量化线程中抢占任务、计算、保存结果并写终态。
                quantTaskExecutor.execute(() -> riskTaskWorker.execute(task.getId(), userId, accountId, days, traceId));
            } catch (RejectedExecutionException ex) {
                taskService.reject(task.getId());
            }
        }
        // 投递后重新读取数据库，确保队列拒绝等状态变化能够立即反映在202响应里。
        QuantTaskView view = taskService.view(taskService.requireOwned(userId, task.getId()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success("风险评估任务已受理", view, traceId));
    }

    /** 从认证主体读取用户 ID。 */
    private Long userId(Authentication authentication) { return Long.valueOf(authentication.getName()); }
    /** 获取当前请求链路 ID。 */
    private String traceId(HttpServletRequest request) { return String.valueOf(request.getAttribute("traceId")); }
    /** 风险任务请求体；days 为空时使用 250 天。 */
    public record MapRequest(Integer days) {}
}
