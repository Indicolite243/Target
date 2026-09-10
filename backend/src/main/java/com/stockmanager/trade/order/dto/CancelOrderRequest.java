package com.stockmanager.trade.order.dto;

/**
 * 撤单请求体。
 *
 * <p>撤单真正依赖路径中的本地 orderId 和请求头幂等键；reason只是可选的人类可读说明，
 * 不允许客户端用它覆盖数据库中的外部订单号。</p>
 *
 * @param reason 用户填写的可选撤单原因；不参与券商订单定位
 */
public record CancelOrderRequest(String reason) {
}
