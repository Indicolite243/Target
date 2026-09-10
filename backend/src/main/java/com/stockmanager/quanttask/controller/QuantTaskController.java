package com.stockmanager.quanttask.controller;

import com.stockmanager.common.response.ApiResponse;
import com.stockmanager.common.exception.BusinessException;
import com.stockmanager.quanttask.entity.QuantTask;
import com.stockmanager.quanttask.service.QuantTaskService;
import com.stockmanager.quanttask.service.QuantTaskResultService;
import com.stockmanager.quanttask.vo.QuantTaskView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 风险、归因和回测共用的异步任务查询、结果读取与取消接口。
 */
@RestController
@RequestMapping("/api/v1/quant-tasks")
public class QuantTaskController {
    private final QuantTaskService taskService;
    private final QuantTaskResultService taskResultService;

    /** 注入任务状态查询和完整结果路由服务。 */
    public QuantTaskController(QuantTaskService taskService, QuantTaskResultService taskResultService) {
        this.taskService = taskService;
        this.taskResultService = taskResultService;
    }

    /** 查询属于当前用户的任务进度和摘要。 */
    @GetMapping("/{taskId}")
    public ApiResponse<QuantTaskView> detail(
            // 路径中的任务ID来自任务提交接口；QuantTaskView会把Snowflake Long序列化为字符串。
            @PathVariable Long taskId,
            // JWT过滤器放入的认证主体，name保存当前用户ID。
            Authentication authentication,
            // 用于读取TraceIdFilter生成的链路号。
            HttpServletRequest request) {
        // requireOwned 同时按 userId 和 taskId 查询，既判断存在性，也阻止读取其他用户的任务。
        return ApiResponse.success(taskService.view(taskService.requireOwned(userId(authentication), taskId)),
                traceId(request));
    }

    /** 读取已成功任务的完整持久化结果。 */
    @GetMapping("/{taskId}/result")
    public ApiResponse<Map<String, Object>> result(
            @PathVariable Long taskId,
            Authentication authentication,
            HttpServletRequest request) {
        // ResultService会先检查任务归属和SUCCEEDED状态，再按resultType路由到对应结果表。
        return ApiResponse.success(taskResultService.requireResult(userId(authentication), taskId), traceId(request));
    }

    /**
     * 取消尚未开始的排队任务；运行中任务不能伪装成已取消，因此返回冲突状态。
     */
    @PostMapping("/{taskId}/cancel")
    public ApiResponse<QuantTaskView> cancel(
            @PathVariable Long taskId,
            Authentication authentication,
            HttpServletRequest request) {
        // 用户ID只从可信认证主体取得，不允许调用方通过请求参数指定任务所有者。
        Long userId = userId(authentication);
        // 首次查询完成任务归属校验，并取得做状态分支所需的当前快照。
        QuantTask task = taskService.requireOwned(userId, taskId);
        if (QuantTaskService.PENDING.equals(task.getStatus())) {
            // 条件更新只允许 PENDING -> CANCELLED；若Worker同时抢占成功，这里不会覆盖RUNNING。
            taskService.cancelPending(taskId);
            // 条件更新后必须重新查询，不能把旧PENDING对象直接返回给前端。
            task = taskService.requireOwned(userId, taskId);
        }
        if (QuantTaskService.RUNNING.equals(task.getStatus())) {
            // 当前线程池没有可靠的跨线程/跨Python进程中断协议，因此明确返回409而不伪造取消成功。
            throw new BusinessException(409802, "任务已开始执行，当前版本不支持强制终止；请等待结果或稍后重新提交。",
                    HttpStatus.CONFLICT);
        }
        // 对SUCCEEDED/FAILED等终态重复取消保持幂等读取语义：不再改状态，只提示无需取消。
        String message = QuantTaskService.CANCELLED.equals(task.getStatus()) ? "任务已取消" : "任务已结束，无需取消";
        return ApiResponse.success(message, taskService.view(task), traceId(request));
    }

    /** 从认证主体读取用户 ID。 */
    private Long userId(Authentication authentication) {
        // JwtAuthenticationFilter已验证Token并把数据库用户ID写入name，此处只做类型转换。
        return Long.valueOf(authentication.getName());
    }
    /** 获取当前请求链路 ID。 */
    private String traceId(HttpServletRequest request) {
        // TraceId只用于可观测性，不参与任务归属和业务幂等判断。
        return String.valueOf(request.getAttribute("traceId"));
    }
}
