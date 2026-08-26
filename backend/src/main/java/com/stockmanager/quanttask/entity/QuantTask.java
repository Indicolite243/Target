package com.stockmanager.quanttask.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("quant_task")
public class QuantTask {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long accountId;
    private String taskType;
    private String status;
    private Integer progress;
    private String stage;
    private String inputDataVersion;
    private String requestJson;
    private String resultType;
    private Long resultId;
    private String resultSummaryJson;
    private String errorCode;
    private String errorMessage;
    private Integer retryCount;
    private Integer maxRetries;
    private String idempotencyKey;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime updatedAt;
}
