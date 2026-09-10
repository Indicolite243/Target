package com.stockmanager.trade.order.vo;

import java.time.LocalDateTime;

/**
 * 用户可见订单快照。
 *
 * <p>所有ID、数量和价格均以字符串表示，避免浏览器Number和JavaScript浮点数精度问题；
 * message用于说明本次操作是首次受理、幂等复用、明确拒绝还是状态待确认。</p>
 *
 * @param orderId 本地订单ID
 * @param clientOrderNo 应用生成的客户端订单号
 * @param externalOrderNo QMT返回的外部委托号
 * @param accountId 交易账户ID
 * @param symbol 证券代码
 * @param securityName 证券名称
 * @param side BUY或SELL
 * @param orderType LIMIT或MARKET
 * @param quantity 委托数量
 * @param price 委托价格，市价单可为空
 * @param filledQuantity 累计成交数量
 * @param averageFilledPrice 平均成交价格
 * @param status 应用标准状态
 * @param environment SIMULATION或REAL
 * @param createdAt 本地意图创建时间
 * @param updatedAt 最近状态更新时间
 * @param message 本次操作的提示消息
 */
public record OrderView(String orderId, String clientOrderNo, String externalOrderNo, String accountId,
                        String symbol, String securityName, String side, String orderType, String quantity,
                        String price, String filledQuantity, String averageFilledPrice, String status,
                        String environment, LocalDateTime createdAt, LocalDateTime updatedAt, String message) {
}
