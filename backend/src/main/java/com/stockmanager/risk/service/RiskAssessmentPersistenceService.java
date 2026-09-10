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

/**
 * 风险评估结果唯一写入边界。
 * 计算层可以来自 FastAPI 或兼容旧数据，但最终都在这里统一保存指标 JSON、规则版本、
 * 算法版本、数据版本和警告，保证一次风险结果能够复现当时的输入口径。
 */
@Service
public class RiskAssessmentPersistenceService {
    private final RiskAssessmentMapper mapper;
    private final ObjectMapper objectMapper;

    /** 注入风险结果 Mapper 和 JSON 编解码器。 */
    public RiskAssessmentPersistenceService(RiskAssessmentMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /** 保存一次完整风险评估，并返回已反序列化的接口视图。 */
    @Transactional
    public RiskAssessmentView save(Long accountId, LocalDate rangeStart, LocalDate rangeEnd,
                                   String riskLevel, BigDecimal riskScore, Map<String, Object> metrics,
                                   List<String> recommendations, Map<String, Object> sample,
                                   String dataVersion, Integer ruleVersion, String algorithmVersion,
                                   String environment, String dataSource, List<String> warnings,
                                   LocalDateTime calculatedAt) {
        // record()集中完成空值归一化和JSON序列化，避免同步/异步调用产生不同存储格式。
        RiskAssessmentRecord record = record(accountId, rangeStart, rangeEnd, riskLevel, riskScore, metrics,
                recommendations, sample, dataVersion, ruleVersion, algorithmVersion, environment, dataSource,
                warnings, calculatedAt, null);
        // insert回填Snowflake主键，随后View和异步Worker都使用该ID建立引用。
        mapper.insert(record);
        return view(record);
    }

    /** 把风险评估实体中的 JSON 字段恢复为 Map/List 接口模型。 */
    public RiskAssessmentView view(RiskAssessmentRecord record) {
        // 数据库中的 JSON 字段在出站时还原为 Map/List，避免控制器重复解析。
        return new RiskAssessmentView(record.getId(), record.getAccountId(), record.getRangeStart(),
                record.getRangeEnd(), record.getRiskLevel(), record.getRiskScore(), map(record.getMetricsJson()),
                strings(record.getRecommendationsJson()), map(record.getSampleJson()), record.getDataVersion(),
                record.getRuleVersion(), record.getAlgorithmVersion(), record.getEnvironment(), record.getDataSource(),
                strings(record.getWarningsJson()), record.getCalculatedAt());
    }

    /** 将异步任务 ID 关联到已保存的风险评估。 */
    public void attachTask(Long assessmentId, Long taskId) {
        // 异步 Worker 在结果生成后补绑 taskId；结果主键和任务主键因此可以双向追溯。
        if (assessmentId == null || taskId == null) return;
        // 风险计算可能由同步接口触发，所以先保存结果、异步Worker再补taskId。
        RiskAssessmentRecord record = mapper.selectById(assessmentId);
        if (record == null) return;
        record.setTaskId(taskId);
        mapper.updateById(record);
    }

    /** 按主键读取风险结果，不存在时返回统一 404。 */
    public RiskAssessmentView requireView(Long assessmentId) {
        RiskAssessmentRecord record = mapper.selectById(assessmentId);
        if (record == null) throw new BusinessException(404401, "风险评估结果不存在", HttpStatus.NOT_FOUND);
        return view(record);
    }

    /** 构造风险评估数据库实体并统一填充版本、来源与时间字段。 */
    private RiskAssessmentRecord record(Long accountId, LocalDate rangeStart, LocalDate rangeEnd,
                                        String riskLevel, BigDecimal riskScore, Map<String, Object> metrics,
                                        List<String> recommendations, Map<String, Object> sample,
                                        String dataVersion, Integer ruleVersion, String algorithmVersion,
                                        String environment, String dataSource, List<String> warnings,
                                        LocalDateTime calculatedAt, String sourceRecordId) {
        // 结构化列支持筛选和审计，复杂可变结构统一存入JSON列。
        RiskAssessmentRecord record = new RiskAssessmentRecord();
        record.setAccountId(accountId);
        record.setRangeStart(rangeStart);
        record.setRangeEnd(rangeEnd);
        record.setRiskLevel(blank(riskLevel, "LOW"));
        record.setRiskScore(riskScore == null ? BigDecimal.ZERO : riskScore);
        // 四类JSON字段都写入非null空对象/数组，读取端不需要处理数据库null。
        record.setMetricsJson(json(metrics == null ? Map.of() : metrics));
        record.setRecommendationsJson(json(recommendations == null ? List.of() : recommendations));
        record.setSampleJson(json(sample == null ? Map.of() : sample));
        record.setWarningsJson(json(warnings == null ? List.of() : warnings));
        // 数据、规则、算法三个版本共同描述一次可复现风险结果。
        record.setDataVersion(blank(dataVersion, "unknown"));
        record.setRuleVersion(ruleVersion);
        record.setAlgorithmVersion(algorithmVersion);
        record.setEnvironment(environment);
        record.setDataSource(dataSource);
        record.setSourceRecordId(sourceRecordId);
        // calculatedAt描述算法完成时间，createdAt描述数据库持久化时间，两者语义不同。
        record.setCalculatedAt(calculatedAt == null ? LocalDateTime.now() : calculatedAt);
        record.setCreatedAt(LocalDateTime.now());
        return record;
    }

    /** 序列化任意风险结果字段为 JSON。 */
    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalArgumentException("风险结果JSON序列化失败", ex); }
    }

    /** 把 JSON 对象字段恢复为 Map。 */
    private Map<String, Object> map(String value) {
        // 历史脏JSON不应导致整个列表接口崩溃，解析失败降级为空对象。
        try { return objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (Exception ignored) { return Map.of(); }
    }

    /** 把 JSON 数组字段恢复为字符串列表。 */
    private List<String> strings(String value) {
        // 建议和警告始终按字符串数组向前端输出。
        try { return objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (Exception ignored) { return List.of(); }
    }

    /** 空白文本使用业务默认值。 */
    private String blank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
}
