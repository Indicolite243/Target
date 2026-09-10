/** 订单状态历史Mapper包：保存状态机观测轨迹。 */
package com.stockmanager.trade.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stockmanager.trade.order.entity.TradeOrderStatusHistory;
import org.apache.ibatis.annotations.Mapper;

/**
 * trade_order_status_history 订单状态历史表的数据访问接口。
 *
 * <p>状态历史按追加方式写入，Service只在状态/成交字段发生变化时调用insert，
 * 页面时间线通过selectList按observed_at升序读取。</p>
 */
@Mapper
public interface TradeOrderStatusHistoryMapper extends BaseMapper<TradeOrderStatusHistory> {
    // 不覆盖任何自定义SQL，避免把状态迁移规则放进Mapper层。
}
