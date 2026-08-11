package com.stockmanager.market.service;

import java.util.List;
import java.util.Map;

public interface MarketService {
    Map<String, Object> latestQuotes(List<String> symbols, boolean allowStale, String traceId);
}
