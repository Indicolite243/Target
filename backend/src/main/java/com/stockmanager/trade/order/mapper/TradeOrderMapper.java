/** 订单主表Mapper包：只负责数据库访问，不承载QMT调用和状态业务规则。 */
package com.stockmanager.trade.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stockmanager.trade.order.entity.TradeOrder;
import org.apache.ibatis.annotations.Mapper;

/**
 * trade_order 订单主表的数据访问接口。
 *
 * <p>继承MyBatis-Plus的BaseMapper后，自动获得insert、selectById、selectPage、
 * updateById等通用CRUD能力；复杂查询由Service使用LambdaQueryWrapper组装。</p>
 */
@Mapper
public interface TradeOrderMapper extends BaseMapper<TradeOrder> {
    // 当前没有额外SQL：通用CRUD已经覆盖订单主表所需操作。
}
