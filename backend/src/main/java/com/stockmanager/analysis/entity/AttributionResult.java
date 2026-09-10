package com.stockmanager.analysis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 已完成业绩归因的持久化实体。
 *
 * <p>常用检索口径拆成独立列，完整的证券/行业贡献明细保存到JSON；结果一旦生成不再原地修改，
 * 前端通过所属quant_task读取对应报告。</p>
 */
@Data
@TableName("attribution_result")
public class AttributionResult {
    /** MyBatis-Plus生成的Snowflake结果主键。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 唯一关联异步量化任务，防止Worker重复消费生成两份结果。 */
    private Long taskId;
    /** 被分析的本地账户主键。 */
    private Long accountId;
    /** 归因维度，例如ASSET、INDUSTRY。 */
    private String dimension;
    /** 输入数据来源，例如MYSQL或QMT。 */
    private String source;
    /** 实际参与计算的数据起始日期。 */
    private LocalDate rangeStart;
    /** 实际参与计算的数据结束日期。 */
    private LocalDate rangeEnd;
    /** 输入快照版本，用于说明结果基于哪批数据。 */
    private String dataVersion;
    /** 完整归因结果JSON，包括明细行、汇总、警告和计算方法。 */
    private String resultJson;
    /** 结果首次写入时间。 */
    private LocalDateTime createdAt;
    /** 结果最后更新时间；当前不可变模型通常与createdAt一致。 */
    private LocalDateTime updatedAt;
}
