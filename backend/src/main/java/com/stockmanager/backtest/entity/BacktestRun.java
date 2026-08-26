package com.stockmanager.backtest.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("backtest_run")
public class BacktestRun {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long taskId;
    private Long userId;
    private String strategyFilename;
    private String engineType;
    private String benchmarkSymbol;
    private LocalDate startDate;
    private LocalDate endDate;
    /** Relative task directory only; never expose an arbitrary filesystem path. */
    private String runtimePath;
    private String resultJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
