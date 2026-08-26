package com.stockmanager.analysis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Full attribution payload is immutable and retrievable through its owning quant task. */
@Data
@TableName("attribution_result")
public class AttributionResult {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long taskId;
    private Long accountId;
    private String dimension;
    private String source;
    private LocalDate rangeStart;
    private LocalDate rangeEnd;
    private String dataVersion;
    private String resultJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
