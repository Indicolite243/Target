package com.stockmanager.trade.order.vo;

import java.util.List;

public record OrderPageView(List<OrderView> items, long page, long pageSize, long total,
                            long totalPages, boolean hasNext) {
}
