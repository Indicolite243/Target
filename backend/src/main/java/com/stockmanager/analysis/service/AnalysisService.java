package com.stockmanager.analysis.service;

import java.util.Map;

/** 组合配置、时间对比和收益归因的分析服务契约。 */
public interface AnalysisService {
    /** 计算当前组合在指定维度下的配置分布。 */
    Map<String, Object> allocation(Long userId, Long accountId, String dimension, String source);
    /** 计算真实快照或模拟回放口径的时间区间对比。 */
    Map<String, Object> periodComparison(Long userId, Long accountId, String periodType,
                                         String from, String to, String granularity,
                                         String calculationMode, String traceId);
    /** 计算证券与行业收益贡献。 */
    Map<String, Object> attribution(Long userId, Long accountId, String dimension,
                                    String source, String from, String to, String traceId);
}
