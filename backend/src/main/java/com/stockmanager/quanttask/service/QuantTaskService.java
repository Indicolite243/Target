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

/**
 * 量化任务的持久化状态机。状态写入 MySQL，线程池只负责“执行”，这样页面刷新后仍能看到任务进度。
 * 当前任务状态的生命周期：PENDING -> RUNNING -> SUCCEEDED/FAILED；PENDING 也可以被取消。
 *
 * <pre>
 * 创建任务 -> PENDING --数据库 CAS--> RUNNING --Worker 完成--> SUCCEEDED
 *             |                                  \--异常--> FAILED
 *             \--取消（仅未开始）------------------> CANCELLED
 * </pre>
 */
@Service
public class QuantTaskService {
    /** 已建档、尚未被Worker抢占，可安全取消。 */
    public static final String PENDING = "PENDING";
    /** Worker已经获得执行权，当前版本不能强制取消。 */
    public static final String RUNNING = "RUNNING";
    /** 结果已经完整持久化，可以读取resultType所指向的结果。 */
    public static final String SUCCEEDED = "SUCCEEDED";
    /** 执行或投递失败，错误信息已写入任务记录。 */
    public static final String FAILED = "FAILED";
    /** 用户在Worker开始前取消成功。 */
    public static final String CANCELLED = "CANCELLED";

    private final QuantTaskMapper mapper;
    private final ObjectMapper objectMapper;

    /** 注入任务 Mapper 和 JSON 编解码器。 */
    public QuantTaskService(QuantTaskMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /** 创建或按用户、类型和幂等键复用一项量化任务。 */
    public QuantTask create(Long userId, Long accountId, String taskType, Map<String, Object> request,
                            String idempotencyKey) {
        // 相同用户、任务类型和幂等键直接复用旧任务，避免前端重试创建重复回测或风险计算。
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            QuantTask existing = mapper.selectOne(Wrappers.<QuantTask>lambdaQuery()
                    .eq(QuantTask::getUserId, userId).eq(QuantTask::getTaskType, taskType)
                    .eq(QuantTask::getIdempotencyKey, idempotencyKey));
            if (existing != null) return existing;
        }
        // 所有初始时间使用同一个now，避免同一行createdAt与updatedAt出现无意义的微小差异。
        LocalDateTime now = LocalDateTime.now();
        // 新任务只保存控制面信息；各业务的完整输入文件或大数据由自己的模块管理。
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
        // insert时MyBatis-Plus生成Snowflake ID并回填到task.id。
        mapper.insert(task);
        return task;
    }

    /** 按任务 ID 和用户 ID 查询，防止跨用户读取任务。 */
    public QuantTask requireOwned(Long userId, Long taskId) {
        // 在同一条SQL中加入所有者条件，避免“先查任务、再判断用户”产生越权数据暴露窗口。
        QuantTask task = mapper.selectOne(Wrappers.<QuantTask>lambdaQuery()
                .eq(QuantTask::getId, taskId).eq(QuantTask::getUserId, userId));
        if (task == null) throw new BusinessException(404801, "量化任务不存在", HttpStatus.NOT_FOUND);
        return task;
    }

    /** 使用数据库条件更新把 PENDING 原子转换为 RUNNING。 */
    public boolean markRunning(Long taskId) {
        /*
         * 这是数据库版的 CAS：只有仍处于 PENDING 的任务才能被某个 Worker 抢占。
         * 返回值为 false 说明任务已被其他 Worker 抢走、已取消，或任务不存在；调用方不能继续执行。
         */
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

    /** 将运行中任务标记为成功，并保存结果类型、结果 ID 和摘要。 */
    public void succeed(Long taskId, String resultType, Long resultId, Map<String, Object> summary) {
        // Worker 结束后先重新读取任务；如果用户已经取消，不能把 CANCELLED 覆盖为 SUCCEEDED。
        // Worker只有taskId，任务归属已经在提交阶段确定；终态写入按主键读取即可。
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

    /** 将任务标记为失败并保存截断后的错误码和消息。 */
    public void fail(Long taskId, String code, String message) {
        // 错误信息截断后再落库，避免异常堆栈或上游长响应撑大任务表。
        // 失败写入同样先读取最新状态，已取消任务不能被迟到异常覆盖。
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

    /** 在线程池拒绝执行时把任务标记为明确失败。 */
    public void reject(Long taskId) {
        fail(taskId, "TASK_QUEUE_FULL", "量化任务队列已满，请稍后重试");
    }

    /**
     * 只有还没有被 Worker 抢占的任务才能安全取消。
     * 条件更新保证取消请求不会覆盖已经进入 RUNNING 的任务。
     */
    /** 仅在任务仍为 PENDING 时原子取消。 */
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
     * 当前线程池队列位于 JVM 内存中，PENDING/RUNNING 任务无法跨进程重启恢复。
     * 启动时将遗留任务标记为 FAILED，比让页面永久显示“执行中”或盲目重放回测更安全；
     * 未来如果接入可靠消息队列，这里可以改成恢复或重新投递。
     */
    /** 应用重启后把遗留 PENDING/RUNNING 任务收敛为失败，避免伪装仍在执行。 */
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

    /** 把任务实体转换为前端轮询视图。 */
    public QuantTaskView view(QuantTask task) {
        // 实体中的requestJson、重试计数和内部更新时间不属于前端高频轮询协议，因此不放入View。
        return new QuantTaskView(task.getId(), task.getAccountId(), task.getTaskType(), task.getStatus(),
                task.getProgress(), task.getStage(), task.getResultType(), task.getResultId(),
                map(task.getResultSummaryJson()), task.getErrorCode(), task.getErrorMessage(), task.getCreatedAt(),
                task.getStartedAt(), task.getFinishedAt());
    }

    /** 序列化任务输入和结果摘要。 */
    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception ex) { throw new IllegalArgumentException("量化任务JSON序列化失败", ex); }
    }

    /** 反序列化任务摘要 JSON。 */
    private Map<String, Object> map(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try { return objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (Exception ignored) { return Map.of(); }
    }

    /** 限制数据库错误字段长度，避免外部异常堆栈撑大任务记录。 */
    private String truncate(String value, int max) {
        if (value == null) return "任务执行失败";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
