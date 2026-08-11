package com.stockmanager.market.controller;

import com.stockmanager.common.response.ApiResponse;
import com.stockmanager.market.service.MarketService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/v1/market")
public class MarketController {
    private final MarketService marketService;

    public MarketController(MarketService marketService) {
        this.marketService = marketService;
    }

    @GetMapping("/quotes")
    public ApiResponse<Map<String, Object>> quotes(
            @RequestParam @Size(max = 2000) String symbols,
            @RequestParam(defaultValue = "true") boolean allowStale,
            HttpServletRequest request) {
        List<String> requested = Arrays.stream(symbols.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).distinct().limit(100).toList();
        return ApiResponse.success(marketService.latestQuotes(requested, allowStale, traceId(request)), traceId(request));
    }

    private String traceId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute("traceId"));
    }
}
