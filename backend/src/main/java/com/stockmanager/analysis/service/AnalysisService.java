package com.stockmanager.analysis.service;

import java.util.Map;

public interface AnalysisService {
    Map<String, Object> allocation(Long userId, Long accountId, String dimension, String source);
    Map<String, Object> periodComparison(Long userId, Long accountId, String periodType,
                                         String from, String to, String granularity,
                                         String calculationMode, String traceId);
    Map<String, Object> attribution(Long userId, Long accountId, String dimension,
                                    String source, String from, String to, String traceId);
}
