package com.stockmanager.market.service.impl;

import com.stockmanager.integration.quant.QuantClient;
import com.stockmanager.market.service.MarketService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class MarketServiceImpl implements MarketService {
    private final QuantClient quantClient;

    public MarketServiceImpl(QuantClient quantClient) {
        this.quantClient = quantClient;
    }

    @Override
    public Map<String, Object> latestQuotes(List<String> symbols, boolean allowStale, String traceId) {
        return quantClient.quotes(Map.of(
                "symbols", symbols,
                "fields", List.of("lastPrice", "openPrice", "highPrice", "lowPrice", "volume", "amount"),
                "preferredSource", "QMT",
                "environment", "SIMULATION",
                "allowStale", allowStale
        ), traceId);
    }
}
