package com.stockmanager.backtest.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stockmanager.backtest.entity.BacktestRun;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BacktestRunMapper extends BaseMapper<BacktestRun> {
}
