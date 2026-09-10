/** 订单审计Mapper包：把操作记录持久化为不可变追加行。 */
package com.stockmanager.trade.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stockmanager.trade.order.entity.TradeOrderAudit;
import org.apache.ibatis.annotations.Mapper;

/**
 * trade_order_audit 订单操作审计表的数据访问接口。
 *
 * <p>审计记录只在Service事务内通过insert追加，列表查询通过BaseMapper的selectList完成；
 * 这里不放删除方法，避免误删高风险操作证据。</p>
 */
@Mapper
public interface TradeOrderAuditMapper extends BaseMapper<TradeOrderAudit> {
    // BaseMapper提供insert/selectList/selectOne，已满足审计追加和撤单幂等查询。
}
