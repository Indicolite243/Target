package com.stockmanager.risk.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Stable API representation; JSON MySQL columns remain normal nested JSON for the frontend. */
public record RiskAssessmentView(
        Long id,
        Long accountId,
        LocalDate rangeStart,
        LocalDate rangeEnd,
        String riskLevel,
        BigDecimal riskScore,
        Map<String, Object> metrics,
        List<String> recommendations,
        Map<String, Object> sample,
        String dataVersion,
        Integer ruleVersion,
        String algorithmVersion,
        String environment,
        String dataSource,
        List<String> warnings,
        LocalDateTime calculatedAt
) {
}
