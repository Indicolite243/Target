package com.stockmanager.risk.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 风险评估稳定API视图。
 * 数据库中的JSON字符串在持久化服务中恢复为Map/List，前端看到的是正常嵌套JSON而不是转义文本。
 */
public record RiskAssessmentView(
        /** 风险结果主键。 */
        Long id,
        /** 所属账户主键。 */
        Long accountId,
        /** 样本起始日期。 */
        LocalDate rangeStart,
        /** 样本结束日期。 */
        LocalDate rangeEnd,
        /** LOW、MEDIUM或HIGH。 */
        String riskLevel,
        /** 0到100的规则综合分。 */
        BigDecimal riskScore,
        /** 指标名到value/threshold/status/available结构的映射。 */
        Map<String, Object> metrics,
        /** 根据风险等级生成的建议。 */
        List<String> recommendations,
        /** 实际样本信息。 */
        Map<String, Object> sample,
        /** 输入数据版本。 */
        String dataVersion,
        /** Spring分级规则版本。 */
        Integer ruleVersion,
        /** Python计算算法版本。 */
        String algorithmVersion,
        /** 账户执行环境。 */
        String environment,
        /** 历史数据来源。 */
        String dataSource,
        /** 样本缺口或降级说明。 */
        List<String> warnings,
        /** 指标完成时间。 */
        LocalDateTime calculatedAt
) {
}
