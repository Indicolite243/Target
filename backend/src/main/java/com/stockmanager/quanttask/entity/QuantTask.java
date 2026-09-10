package com.stockmanager.quanttask.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 风险、归因和回测共用的异步任务状态实体。
 * 任务状态保存在 MySQL，因此页面刷新或应用重连后仍可继续查询。
 */
@Data
@TableName("quant_task")
public class QuantTask {
    /** 任务 Snowflake 主键。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 任务所属用户。 */
    private Long userId;
    /** 可选关联账户；回测任务可为空。 */
    private Long accountId;
    /** 任务类型：RISK、ATTRIBUTION、BACKTEST。 */
    private String taskType;
    /** 生命周期状态。 */
    private String status;
    /** 0-100 的展示进度。 */
    private Integer progress;
    /** 当前阶段说明。 */
    private String stage;
    /** 输入数据版本，用于复现计算口径。 */
    private String inputDataVersion;
    /** 脱敏后的请求摘要 JSON。 */
    private String requestJson;
    /** 完整结果类型。 */
    private String resultType;
    /** 结果表主键。 */
    private Long resultId;
    /** 前端轮询可直接展示的小型结果摘要 JSON。 */
    private String resultSummaryJson;
    /** 失败错误码。 */
    private String errorCode;
    /** 截断后的安全错误消息。 */
    private String errorMessage;
    /** 已重试次数；当前框架保留字段但不盲目重放外部任务。 */
    private Integer retryCount;
    /** 最大重试次数。 */
    private Integer maxRetries;
    /** 用户和任务类型范围内的幂等键。 */
    private String idempotencyKey;
    /** 创建时间。 */
    private LocalDateTime createdAt;
    /** 开始执行时间。 */
    private LocalDateTime startedAt;
    /** 进入终态时间。 */
    private LocalDateTime finishedAt;
    /** 最近更新时间。 */
    private LocalDateTime updatedAt;
}
