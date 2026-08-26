package com.stockmanager.trade.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stockmanager.trade.order.entity.TradeOrderStatusHistory;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TradeOrderStatusHistoryMapper extends BaseMapper<TradeOrderStatusHistory> {
}
