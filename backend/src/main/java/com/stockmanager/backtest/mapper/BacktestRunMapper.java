package com.stockmanager.backtest.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stockmanager.backtest.entity.BacktestRun;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code backtest_run} 回测运行结果表的数据访问接口。
 *
 * <p>回测执行在 Python 子进程中完成，本 Mapper 只保存 Java 侧归档结果和文件引用，
 * 不参与策略解析。主键读取、插入以及按任务/账户条件查询都由 BaseMapper 与 Wrapper 提供。</p>
 */
@Mapper
public interface BacktestRunMapper extends BaseMapper<BacktestRun> {
    // 目前没有复杂联表 SQL；查询条件集中由 BacktestRunPersistenceService 构造。
}
