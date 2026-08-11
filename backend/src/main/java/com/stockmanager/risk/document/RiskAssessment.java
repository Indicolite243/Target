package com.stockmanager.risk.document;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Document("risk_assessments")
@CompoundIndex(name = "idx_risk_account_time", def = "{'accountId': 1, 'calculatedAt': -1}")
public class RiskAssessment {
    @Id
    private String id;
    private Long accountId;
    private String riskLevel;
    private String riskScore;
    private Map<String, Object> metrics;
    private List<String> recommendations;
    private Map<String, Object> sample;
    private String dataVersion;
    private Integer ruleVersion;
    private String algorithmVersion;
    private String environment;
    private String dataSource;
    private List<String> warnings;
    private LocalDateTime calculatedAt;
}
