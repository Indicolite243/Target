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

/**
 * 资产配置、时间对比和收益归因接口。
 *
 * <p>轻量读取保持同步返回；需要持久化和轮询的归因任务进入有界量化线程池，避免占用 HTTP 线程。</p>
 */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}")
public class AnalysisController {
    private final AnalysisService analysisService;
    private final QuantTaskService taskService;
    private final AttributionTaskWorker attributionTaskWorker;
    private final ThreadPoolTaskExecutor quantTaskExecutor;

    /** 注入同步分析、异步任务状态机、归因 Worker 和量化线程池。 */
    public AnalysisController(AnalysisService analysisService, QuantTaskService taskService,
                              AttributionTaskWorker attributionTaskWorker,
                              @Qualifier("quantTaskExecutor") ThreadPoolTaskExecutor quantTaskExecutor) {
        this.analysisService = analysisService;
        this.taskService = taskService;
        this.attributionTaskWorker = attributionTaskWorker;
        this.quantTaskExecutor = quantTaskExecutor;
    }

    /** 同步返回当前组合的资产、行业或市场配置。 */
    @GetMapping({"/allocations", "/analyses/allocation"})
    public ApiResponse<Map<String, Object>> allocation(
            // 本地账户主键；Service还会与JWT用户ID联合校验归属。
            @PathVariable Long accountId,
            // ASSET_CLASS、INDUSTRY、REGION/MARKET决定分组方式。
            @RequestParam(defaultValue = "ASSET_CLASS") String dimension,
            // source用于结果来源标签兼容，不允许绕过账户权限。
            @RequestParam(defaultValue = "qmt") String source,
            Authentication authentication, HttpServletRequest request) {
        // 当前配置属于轻量同步计算，只读取账户和持仓当前事实表。
        return ApiResponse.success(analysisService.allocation(userId(authentication), accountId,
                dimension.toUpperCase(), source.toLowerCase()), traceId(request));
    }

    /** 同步返回 MySQL 快照或模拟行情回放的时间区间对比。 */
    @GetMapping("/analyses/period-comparison")
    public ApiResponse<Map<String, Object>> period(
            @PathVariable Long accountId,
            // WEEK默认回看7天，其余值当前默认回看365天。
            @RequestParam(defaultValue = "YEAR") String periodType,
            // 可选ISO日期；为空或非法时Service应用默认区间。
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            // DAILY每日最后一条，ALL返回区间内全部采集快照。
            @RequestParam(defaultValue = "DAILY") String granularity,
            // SNAPSHOT读取真实MySQL快照；SIMULATED固定当前持仓回放QMT日线。
            @RequestParam(defaultValue = "SNAPSHOT") String calculationMode,
            Authentication authentication, HttpServletRequest request) {
        return ApiResponse.success(analysisService.periodComparison(userId(authentication), accountId,
                periodType.toUpperCase(), from, to, granularity.toUpperCase(),
                calculationMode.toUpperCase(), traceId(request)), traceId(request));
    }

    /** 同步计算归因结果，保留给当前前端和兼容调用。 */
    @GetMapping("/analyses/attribution")
    public ApiResponse<Map<String, Object>> attribution(
            @PathVariable Long accountId,
            // ASSET输出证券贡献，INDUSTRY还会进一步聚合行业桶。
            @RequestParam(defaultValue = "ASSET") String dimension,
            // QMT/QMT_LIVE使用当前持仓累计盈亏，MYSQL使用历史快照期间变化。
            @RequestParam(defaultValue = "qmt") String source,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            Authentication authentication, HttpServletRequest request) {
        return ApiResponse.success(analysisService.attribution(userId(authentication), accountId,
                dimension.toUpperCase(), source.toLowerCase(), from, to, traceId(request)), traceId(request));
    }

    /**
     * 创建异步归因任务并尝试提交有界线程池；队列满时任务会被明确标记为拒绝。
     */
    @PostMapping("/analyses/attribution/tasks")
    public ResponseEntity<ApiResponse<QuantTaskView>> submitAttribution(
            @PathVariable Long accountId, @RequestBody(required = false) AttributionTaskRequest body,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication, HttpServletRequest request) {
        // 空请求体使用稳定默认值，避免调用方为了默认归因必须构造冗余JSON。
        String dimension = body == null || body.dimension() == null ? "ASSET" : body.dimension().toUpperCase();
        String source = body == null || body.source() == null ? "MYSQL" : body.source().toUpperCase();
        String from = body == null ? null : body.from();
        String to = body == null ? null : body.to();
        Long userId = userId(authentication);
        String traceId = traceId(request);
        // 先持久化PENDING任务，HTTP返回后页面仍可使用taskId查询进度。
        QuantTask task = taskService.create(userId, accountId, "ATTRIBUTION",
                Map.of("dimension", dimension, "source", source, "from", from == null ? "" : from,
                        "to", to == null ? "" : to), idempotencyKey);
        if (QuantTaskService.PENDING.equals(task.getStatus())) {
            try {
                // 有界量化线程池隔离长分析任务，不占用Tomcat请求线程。
                quantTaskExecutor.execute(() -> attributionTaskWorker.execute(task.getId(), userId, accountId,
                        dimension, source, from, to, traceId));
            } catch (RejectedExecutionException ex) {
                // 队列满时记录明确FAILED状态，前端不会永久停留在等待中。
                taskService.reject(task.getId());
            }
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success("归因分析任务已受理",
                taskService.view(taskService.requireOwned(userId, task.getId())), traceId));
    }

    /** 从认证主体读取用户 ID。 */
    private Long userId(Authentication authentication) { return Long.valueOf(authentication.getName()); }
    /** 获取当前请求链路 ID。 */
    private String traceId(HttpServletRequest request) { return String.valueOf(request.getAttribute("traceId")); }
    /** 异步归因任务的可选输入参数。 */
    public record AttributionTaskRequest(String dimension, String source, String from, String to) {}
}
