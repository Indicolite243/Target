package com.stockmanager.quanttask.vo;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 提供给前端轮询的轻量任务视图。
 *
 * <p>这里只返回状态、进度、错误和小型结果摘要，不返回收益曲线或归因明细等完整结果；
 * 完整结果必须在任务成功后通过 {@code /quant-tasks/{taskId}/result} 单独读取。</p>
 */
public record QuantTaskView(
        /** Snowflake ID超过JavaScript安全整数范围，强制序列化为字符串避免精度丢失。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Long id,
        /** 可选关联账户；回测任务通常为空，同样以字符串保护Long精度。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Long accountId,
        /** 任务类别：RISK、ATTRIBUTION或BACKTEST。 */
        String taskType,
        /** 生命周期状态：PENDING、RUNNING、SUCCEEDED、FAILED或CANCELLED。 */
        String status,
        /** 供界面展示的0到100进度值。 */
        Integer progress,
        /** 当前阶段的人类可读说明。 */
        String stage,
        /** 成功任务的结果路由类型。 */
        String resultType,
        /** 对应结果表主键；部分结果也可能主要通过taskId关联。 */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Long resultId,
        /** 适合轮询返回的小型摘要，不包含完整大数组。 */
        Map<String, Object> resultSummary,
        /** 失败时的稳定错误码。 */
        String errorCode,
        /** 失败时经过长度限制的安全提示。 */
        String errorMessage,
        /** 任务建档时间。 */
        LocalDateTime createdAt,
        /** Worker成功把任务从PENDING抢占为RUNNING的时间。 */
        LocalDateTime startedAt,
        /** 进入SUCCEEDED、FAILED或CANCELLED终态的时间。 */
        LocalDateTime finishedAt
) {
}
