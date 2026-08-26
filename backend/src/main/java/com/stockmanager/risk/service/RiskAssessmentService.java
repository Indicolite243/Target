package com.stockmanager.risk.service;

import com.stockmanager.risk.vo.RiskAssessmentView;

public interface RiskAssessmentService {
    RiskAssessmentView calculate(Long userId, Long accountId, int days, String traceId);
    RiskAssessmentView latestOrCalculate(Long userId, Long accountId, int days,
                                         String startDate, String endDate, String granularity, String traceId);
}
