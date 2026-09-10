package com.stockmanager.trade.order.vo;

import java.util.List;

/**
 * 分页订单历史结果，包含当前页数据、总量和是否还有下一页。
 *
 * @param items 当前页订单快照
 * @param page 实际使用的页码，Service会把小于1的输入归一为1
 * @param pageSize 实际使用的页大小，Service会限制在1到100之间
 * @param total 过滤条件命中的总记录数
 * @param totalPages 根据total和pageSize计算出的总页数
 * @param hasNext 当前页之后是否仍有数据
 */
public record OrderPageView(List<OrderView> items, long page, long pageSize, long total,
                            long totalPages, boolean hasNext) {
}
