package com.stockmanager.risk.service;

import com.stockmanager.risk.vo.RiskAssessmentView;

/** 基于账户历史快照执行风险计算和结果持久化的服务契约。 */
public interface RiskAssessmentService {
    /** 使用最近 days 天的默认日频区间计算风险。 */
    RiskAssessmentView calculate(Long userId, Long accountId, int days, String traceId);
    /** 使用显式日期范围和粒度计算风险。 */
    RiskAssessmentView latestOrCalculate(Long userId, Long accountId, int days,
                                         String startDate, String endDate, String granularity, String traceId);
}
