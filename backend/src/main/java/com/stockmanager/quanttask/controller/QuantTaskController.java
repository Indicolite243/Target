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

@RestController
@RequestMapping("/api/v1/quant-tasks")
public class QuantTaskController {
    private final QuantTaskService taskService;
    private final QuantTaskResultService taskResultService;

    public QuantTaskController(QuantTaskService taskService, QuantTaskResultService taskResultService) {
        this.taskService = taskService;
        this.taskResultService = taskResultService;
    }

    @GetMapping("/{taskId}")
    public ApiResponse<QuantTaskView> detail(@PathVariable Long taskId, Authentication authentication,
                                             HttpServletRequest request) {
        return ApiResponse.success(taskService.view(taskService.requireOwned(userId(authentication), taskId)),
                traceId(request));
    }

    @GetMapping("/{taskId}/result")
    public ApiResponse<Map<String, Object>> result(@PathVariable Long taskId, Authentication authentication,
                                                    HttpServletRequest request) {
        return ApiResponse.success(taskResultService.requireResult(userId(authentication), taskId), traceId(request));
    }

    @PostMapping("/{taskId}/cancel")
    public ApiResponse<QuantTaskView> cancel(@PathVariable Long taskId, Authentication authentication,
                                             HttpServletRequest request) {
        Long userId = userId(authentication);
        QuantTask task = taskService.requireOwned(userId, taskId);
        if (QuantTaskService.PENDING.equals(task.getStatus())) {
            taskService.cancelPending(taskId);
            task = taskService.requireOwned(userId, taskId);
        }
        if (QuantTaskService.RUNNING.equals(task.getStatus())) {
            throw new BusinessException(409802, "任务已开始执行，当前版本不支持强制终止；请等待结果或稍后重新提交。",
                    HttpStatus.CONFLICT);
        }
        String message = QuantTaskService.CANCELLED.equals(task.getStatus()) ? "任务已取消" : "任务已结束，无需取消";
        return ApiResponse.success(message, taskService.view(task), traceId(request));
    }

    private Long userId(Authentication authentication) { return Long.valueOf(authentication.getName()); }
    private String traceId(HttpServletRequest request) { return String.valueOf(request.getAttribute("traceId")); }
}
