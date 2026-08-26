package com.stockmanager.risk.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmanager.risk.entity.RiskAssessmentRecord;
import com.stockmanager.risk.mapper.RiskAssessmentMapper;
import com.stockmanager.risk.vo.RiskAssessmentView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.stockmanager.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** The only writer for MySQL risk results, including the one-time legacy migration. */
@Service
public class RiskAssessmentPersistenceService {
    private final RiskAssessmentMapper mapper;
    private final ObjectMapper objectMapper;

    public RiskAssessmentPersistenceService(RiskAssessmentMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RiskAssessmentView save(Long accountId, LocalDate rangeStart, LocalDate rangeEnd,
                                   String riskLevel, BigDecimal riskScore, Map<String, Object> metrics,
                                   List<String> recommendations, Map<String, Object> sample,
                                   String dataVersion, Integer ruleVersion, String algorithmVersion,
                                   String environment, String dataSource, List<String> warnings,
                                   LocalDateTime calculatedAt) {
        RiskAssessmentRecord record = record(accountId, rangeStart, rangeEnd, riskLevel, riskScore, metrics,
                recommendations, sample, dataVersion, ruleVersion, algorithmVersion, environment, dataSource,
                warnings, calculatedAt, null);
        mapper.insert(record);
        return view(record);
    }

    public RiskAssessmentView view(RiskAssessmentRecord record) {
        return new RiskAssessmentView(record.getId(), record.getAccountId(), record.getRangeStart(),
                record.getRangeEnd(), record.getRiskLevel(), record.getRiskScore(), map(record.getMetricsJson()),
                strings(record.getRecommendationsJson()), map(record.getSampleJson()), record.getDataVersion(),
                record.getRuleVersion(), record.getAlgorithmVersion(), record.getEnvironment(), record.getDataSource(),
                strings(record.getWarningsJson()), record.getCalculatedAt());
    }

    public void attachTask(Long assessmentId, Long taskId) {
        if (assessmentId == null || taskId == null) return;
        RiskAssessmentRecord record = mapper.selectById(assessmentId);
        if (record == null) return;
        record.setTaskId(taskId);
        mapper.updateById(record);
    }

    public RiskAssessmentView requireView(Long assessmentId) {
        RiskAssessmentRecord record = mapper.selectById(assessmentId);
        if (record == null) throw new BusinessException(404401, "风险评估结果不存在", HttpStatus.NOT_FOUND);
        return view(record);
    }

    private RiskAssessmentRecord record(Long accountId, LocalDate rangeStart, LocalDate rangeEnd,
                                        String riskLevel, BigDecimal riskScore, Map<String, Object> metrics,
                                        List<String> recommendations, Map<String, Object> sample,
                                        String dataVersion, Integer ruleVersion, String algorithmVersion,
                                        String environment, String dataSource, List<String> warnings,
                                        LocalDateTime calculatedAt, String sourceRecordId) {
        RiskAssessmentRecord record = new RiskAssessmentRecord();
        record.setAccountId(accountId);
        record.setRangeStart(rangeStart);
        record.setRangeEnd(rangeEnd);
        record.setRiskLevel(blank(riskLevel, "LOW"));
        record.setRiskScore(riskScore == null ? BigDecimal.ZERO : riskScore);
        record.setMetricsJson(json(metrics == null ? Map.of() : metrics));
        record.setRecommendationsJson(json(recommendations == null ? List.of() : recommendations));
        record.setSampleJson(json(sample == null ? Map.of() : sample));
        record.setWarningsJson(json(warnings == null ? List.of() : warnings));
        record.setDataVersion(blank(dataVersion, "unknown"));
        record.setRuleVersion(ruleVersion);
        record.setAlgorithmVersion(algorithmVersion);
        record.setEnvironment(environment);
        record.setDataSource(dataSource);
        record.setSourceRecordId(sourceRecordId);
        record.setCalculatedAt(calculatedAt == null ? LocalDateTime.now() : calculatedAt);
        record.setCreatedAt(LocalDateTime.now());
        return record;
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalArgumentException("风险结果JSON序列化失败", ex); }
    }

    private Map<String, Object> map(String value) {
        try { return objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (Exception ignored) { return Map.of(); }
    }

    private List<String> strings(String value) {
        try { return objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (Exception ignored) { return List.of(); }
    }

    private String blank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
}
