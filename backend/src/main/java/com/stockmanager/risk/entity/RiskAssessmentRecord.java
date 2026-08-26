package com.stockmanager.risk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** MySQL persistence model. JSON payloads are serialized explicitly at the service boundary. */
@Data
@TableName("risk_assessment")
public class RiskAssessmentRecord {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long taskId;
    private Long accountId;
    private LocalDate rangeStart;
    private LocalDate rangeEnd;
    private String riskLevel;
    private BigDecimal riskScore;
    private String metricsJson;
    private String recommendationsJson;
    private String sampleJson;
    private String warningsJson;
    private String dataVersion;
    private Integer ruleVersion;
    private String algorithmVersion;
    private String environment;
    private String dataSource;
    /** Legacy MongoDB _id during the one-time migration; null for newly calculated results. */
    private String sourceRecordId;
    private LocalDateTime calculatedAt;
    private LocalDateTime createdAt;
}
