package com.stockmanager.market.service.impl;

import com.stockmanager.integration.quant.QuantClient;
import com.stockmanager.market.cache.MarketQuoteCache;
import com.stockmanager.market.service.MarketService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MarketServiceImpl implements MarketService {
    private final QuantClient quantClient;
    private final MarketQuoteCache quoteCache;

    public MarketServiceImpl(QuantClient quantClient, MarketQuoteCache quoteCache) {
        this.quantClient = quantClient;
        this.quoteCache = quoteCache;
    }

    @Override
    public Map<String, Object> latestQuotes(List<String> symbols, boolean allowStale, String traceId) {
        List<String> normalizedSymbols = symbols.stream().map(symbol -> symbol.trim().toUpperCase()).distinct().toList();
        Map<String, Map<String, Object>> quotesBySymbol = new LinkedHashMap<>();
        List<String> missingSymbols = new ArrayList<>();
        for (String symbol : normalizedSymbols) {
            if (allowStale) quoteCache.get(symbol).ifPresentOrElse(
                    quote -> quotesBySymbol.put(symbol, quote),
                    () -> missingSymbols.add(symbol));
            else missingSymbols.add(symbol);
        }

        Map<String, Object> upstream = Map.of();
        if (!missingSymbols.isEmpty()) {
            upstream = quantClient.quotes(Map.of(
                "symbols", missingSymbols,
                "fields", List.of("lastPrice", "openPrice", "highPrice", "lowPrice", "volume", "amount"),
                "preferredSource", "QMT",
                "environment", "SIMULATION",
                "allowStale", allowStale
            ), traceId);
            Object rawQuotes = upstream.get("quotes");
            if (rawQuotes instanceof List<?> rows) {
                for (Object row : rows) {
                    if (!(row instanceof Map<?, ?> rawQuote)) continue;
                    Map<String, Object> quote = new LinkedHashMap<>();
                    rawQuote.forEach((key, value) -> quote.put(String.valueOf(key), value));
                    Object rawSymbol = quote.get("symbol");
                    if (rawSymbol == null || String.valueOf(rawSymbol).isBlank()) continue;
                    String symbol = String.valueOf(rawSymbol).trim().toUpperCase();
                    quotesBySymbol.put(symbol, quote);
                    quoteCache.put(symbol, quote);
                }
            }
        }

        List<Map<String, Object>> orderedQuotes = normalizedSymbols.stream()
                .map(quotesBySymbol::get).filter(java.util.Objects::nonNull).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("quotes", orderedQuotes);
        result.put("missingSymbols", upstream.getOrDefault("missingSymbols", List.of()));
        result.put("marketStatus", missingSymbols.isEmpty() ? "REDIS_CACHE" : upstream.getOrDefault("marketStatus", "QMT"));
        return result;
    }
}
