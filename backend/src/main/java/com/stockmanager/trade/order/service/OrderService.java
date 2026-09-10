package com.stockmanager.trade.order.service;

import com.stockmanager.trade.order.dto.SubmitOrderRequest;
import com.stockmanager.trade.order.vo.OrderPageView;
import com.stockmanager.trade.order.vo.OrderTimelineView;
import com.stockmanager.trade.order.vo.OrderView;
import java.time.LocalDateTime;

/**
 * 订单提交、撤单、查询、历史管理和后台对账的应用服务契约。
 *
 * <p>Controller和定时任务只依赖本接口；实现类负责编排本地状态持久化与QuantClient，
 * 从而避免上层直接操作数据库或QMT。</p>
 */
public interface OrderService {
    /** 提交带幂等键的新委托；外部调用结果不确定时返回UNKNOWN。 */
    OrderView submit(Long userId, String idempotencyKey, SubmitOrderRequest request, String traceId);
    /** 分页查询本地订单历史；只读MySQL，不同步访问QMT。 */
    OrderPageView list(Long userId, Long accountId, long page, long pageSize, LocalDateTime start, LocalDateTime endExclusive);
    /** 软删除一条终态历史订单；删除不等于撤销外部委托。 */
    void deleteHistory(Long userId, Long orderId, String traceId);
    /** 按日期范围批量软删除终态历史订单，进行中订单必须保留。 */
    int deleteFilteredHistory(Long userId, LocalDateTime start, LocalDateTime endExclusive, String traceId);
    /** 发起带幂等键的撤单请求；最终撤成状态由后台对账确认。 */
    OrderView cancel(Long userId, Long orderId, String idempotencyKey, String traceId);
    /** 批量对账全部可跟踪QMT订单，并返回本轮发生状态变化的订单数。 */
    int refreshOpenOrderStatuses();
    /** 查询订单状态和审计时间线，必须通过userId校验订单归属。 */
    OrderTimelineView timeline(Long userId, Long orderId);
}
