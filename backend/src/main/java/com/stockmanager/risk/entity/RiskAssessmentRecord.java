package com.stockmanager.risk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * MySQL风险评估持久化实体。
 *
 * <p>风险等级、分数和版本作为结构化列保存；指标、建议、样本和警告以JSON保存，
 * JSON序列化只在PersistenceService边界完成，避免Controller直接操作数据库文本。</p>
 */
@Data
@TableName("risk_assessment")
public class RiskAssessmentRecord {
    /** 风险结果Snowflake主键。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 可选关联异步量化任务；同步latest接口生成的结果可能暂时为空。 */
    private Long taskId;
    /** 被评估账户主键。 */
    private Long accountId;
    /** 实际风险样本开始日期。 */
    private LocalDate rangeStart;
    /** 实际风险样本结束日期。 */
    private LocalDate rangeEnd;
    /** 综合等级：LOW、MEDIUM或HIGH。 */
    private String riskLevel;
    /** Spring阈值规则计算出的可解释综合分数。 */
    private BigDecimal riskScore;
    /** 四项指标及阈值、状态、可用性JSON。 */
    private String metricsJson;
    /** 面向用户的风险建议数组JSON。 */
    private String recommendationsJson;
    /** 样本起止日期和交易日数量JSON。 */
    private String sampleJson;
    /** 历史快照或计算警告数组JSON。 */
    private String warningsJson;
    /** 输入数据版本，用于复现本次计算口径。 */
    private String dataVersion;
    /** Spring风险分级规则版本。 */
    private Integer ruleVersion;
    /** Python数值算法版本。 */
    private String algorithmVersion;
    /** 账户环境，例如SIMULATION。 */
    private String environment;
    /** 历史数据来源，例如mysql_account_snapshots。 */
    private String dataSource;
    /** 一次性迁移时保留的旧MongoDB _id；新计算结果始终为空。 */
    private String sourceRecordId;
    /** 风险指标实际计算完成时间。 */
    private LocalDateTime calculatedAt;
    /** 本记录写入MySQL时间。 */
    private LocalDateTime createdAt;
}
