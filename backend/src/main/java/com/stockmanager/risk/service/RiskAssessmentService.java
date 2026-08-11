package com.stockmanager.risk.service;

import com.stockmanager.risk.document.RiskAssessment;

public interface RiskAssessmentService {
    RiskAssessment calculate(Long userId, Long accountId, int days, String traceId);
    RiskAssessment latestOrCalculate(Long userId, Long accountId, int days,
                                     String startDate, String endDate, String granularity, String traceId);
}
