package com.stockmanager.quanttask.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmanager.common.exception.BusinessException;
import com.stockmanager.quanttask.entity.QuantTask;
import com.stockmanager.quanttask.mapper.QuantTaskMapper;
import com.stockmanager.quanttask.vo.QuantTaskView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class QuantTaskService {
    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    private final QuantTaskMapper mapper;
    private final ObjectMapper objectMapper;

    public QuantTaskService(QuantTaskMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public QuantTask create(Long userId, Long accountId, String taskType, Map<String, Object> request,
                            String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            QuantTask existing = mapper.selectOne(Wrappers.<QuantTask>lambdaQuery()
                    .eq(QuantTask::getUserId, userId).eq(QuantTask::getTaskType, taskType)
                    .eq(QuantTask::getIdempotencyKey, idempotencyKey));
            if (existing != null) return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        QuantTask task = new QuantTask();
        task.setUserId(userId);
        task.setAccountId(accountId);
        task.setTaskType(taskType);
        task.setStatus(PENDING);
        task.setProgress(0);
        task.setStage("已受理，等待执行");
        task.setRequestJson(json(request));
        task.setRetryCount(0);
        task.setMaxRetries(0);
        task.setIdempotencyKey(idempotencyKey);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        mapper.insert(task);
        return task;
    }

    public QuantTask requireOwned(Long userId, Long taskId) {
        QuantTask task = mapper.selectOne(Wrappers.<QuantTask>lambdaQuery()
                .eq(QuantTask::getId, taskId).eq(QuantTask::getUserId, userId));
        if (task == null) throw new BusinessException(404801, "量化任务不存在", HttpStatus.NOT_FOUND);
        return task;
    }

    public boolean markRunning(Long taskId) {
        LocalDateTime now = LocalDateTime.now();
        QuantTask update = new QuantTask();
        update.setStatus(RUNNING);
        update.setProgress(5);
        update.setStage("正在计算");
        update.setStartedAt(now);
        update.setUpdatedAt(now);
        return mapper.update(update, Wrappers.<QuantTask>lambdaUpdate()
                .eq(QuantTask::getId, taskId).eq(QuantTask::getStatus, PENDING)) == 1;
    }

    public void succeed(Long taskId, String resultType, Long resultId, Map<String, Object> summary) {
        QuantTask task = mapper.selectById(taskId);
        if (task == null || CANCELLED.equals(task.getStatus())) return;
        task.setStatus(SUCCEEDED);
        task.setProgress(100);
        task.setStage("已完成");
        task.setResultType(resultType);
        task.setResultId(resultId);
        task.setResultSummaryJson(json(summary));
        task.setFinishedAt(LocalDateTime.now());
        task.setUpdatedAt(task.getFinishedAt());
        mapper.updateById(task);
    }

    public void fail(Long taskId, String code, String message) {
        QuantTask task = mapper.selectById(taskId);
        if (task == null || CANCELLED.equals(task.getStatus())) return;
        task.setStatus(FAILED);
        task.setProgress(100);
        task.setStage("执行失败");
        task.setErrorCode(code);
        task.setErrorMessage(truncate(message, 1000));
        task.setFinishedAt(LocalDateTime.now());
        task.setUpdatedAt(task.getFinishedAt());
        mapper.updateById(task);
    }

    public void reject(Long taskId) {
        fail(taskId, "TASK_QUEUE_FULL", "量化任务队列已满，请稍后重试");
    }

    /** Only a task not yet claimed by a worker can be cancelled safely. */
    public boolean cancelPending(Long taskId) {
        LocalDateTime now = LocalDateTime.now();
        QuantTask update = new QuantTask();
        update.setStatus(CANCELLED);
        update.setProgress(100);
        update.setStage("已取消（尚未开始执行）");
        update.setFinishedAt(now);
        update.setUpdatedAt(now);
        return mapper.update(update, Wrappers.<QuantTask>lambdaUpdate()
                .eq(QuantTask::getId, taskId).eq(QuantTask::getStatus, PENDING)) == 1;
    }

    /**
     * JVM memory is the executor queue, so PENDING/RUNNING jobs cannot survive a restart.
     * Marking them failed is safer than silently presenting a forever-running task or replaying work.
     */
    public int failInterruptedAfterRestart() {
        LocalDateTime now = LocalDateTime.now();
        QuantTask update = new QuantTask();
        update.setStatus(FAILED);
        update.setProgress(100);
        update.setStage("服务重启前任务未完成");
        update.setErrorCode("APPLICATION_RESTARTED");
        update.setErrorMessage("服务重启前任务未完成，请重新提交。");
        update.setFinishedAt(now);
        update.setUpdatedAt(now);
        return mapper.update(update, Wrappers.<QuantTask>lambdaUpdate()
                .in(QuantTask::getStatus, PENDING, RUNNING));
    }

    public QuantTaskView view(QuantTask task) {
        return new QuantTaskView(task.getId(), task.getAccountId(), task.getTaskType(), task.getStatus(),
                task.getProgress(), task.getStage(), task.getResultType(), task.getResultId(),
                map(task.getResultSummaryJson()), task.getErrorCode(), task.getErrorMessage(), task.getCreatedAt(),
                task.getStartedAt(), task.getFinishedAt());
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception ex) { throw new IllegalArgumentException("量化任务JSON序列化失败", ex); }
    }

    private Map<String, Object> map(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try { return objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (Exception ignored) { return Map.of(); }
    }

    private String truncate(String value, int max) {
        if (value == null) return "任务执行失败";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
