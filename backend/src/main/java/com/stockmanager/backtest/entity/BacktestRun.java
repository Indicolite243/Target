package com.stockmanager.backtest.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 已完成回测的可检索元数据和完整结果实体。 */
@Data
@TableName("backtest_run")
public class BacktestRun {
    /** 回测结果主键。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 唯一关联量化任务 ID，也是结果保存幂等键。 */
    private Long taskId;
    /** 提交回测的用户 ID。 */
    private Long userId;
    /** 原始策略文件名。 */
    private String strategyFilename;
    /** 实际使用的回测引擎。 */
    private String engineType;
    /** 可选基准证券代码。 */
    private String benchmarkSymbol;
    /** 回测开始日期。 */
    private LocalDate startDate;
    /** 回测结束日期。 */
    private LocalDate endDate;
    /** 仅保存任务相对目录，不向接口暴露任意本机绝对路径。 */
    private String runtimePath;
    /** 上传时的策略源码副本；运行目录清理后仍可用于解释和给出修改建议。 */
    private String strategySource;
    /** 完整回测报告 JSON。 */
    private String resultJson;
    /** 创建时间。 */
    private LocalDateTime createdAt;
    /** 更新时间。 */
    private LocalDateTime updatedAt;
}
