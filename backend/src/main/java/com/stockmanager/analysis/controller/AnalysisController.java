package com.stockmanager.analysis.controller;

import com.stockmanager.analysis.service.AnalysisService;
import com.stockmanager.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}")
public class AnalysisController {
    private final AnalysisService analysisService;

    public AnalysisController(AnalysisService analysisService) { this.analysisService = analysisService; }

    @GetMapping({"/allocations", "/analyses/allocation"})
    public ApiResponse<Map<String, Object>> allocation(@PathVariable Long accountId,
                                                       @RequestParam(defaultValue = "ASSET_CLASS") String dimension,
                                                       @RequestParam(defaultValue = "qmt") String source,
                                                       Authentication authentication, HttpServletRequest request) {
        return ApiResponse.success(analysisService.allocation(userId(authentication), accountId,
                dimension.toUpperCase(), source.toLowerCase()), traceId(request));
    }

    @GetMapping("/analyses/period-comparison")
    public ApiResponse<Map<String, Object>> period(@PathVariable Long accountId,
                                                   @RequestParam(defaultValue = "YEAR") String periodType,
                                                   @RequestParam(required = false) String from,
                                                   @RequestParam(required = false) String to,
                                                   @RequestParam(defaultValue = "DAILY") String granularity,
                                                   @RequestParam(defaultValue = "SNAPSHOT") String calculationMode,
                                                   Authentication authentication, HttpServletRequest request) {
        return ApiResponse.success(analysisService.periodComparison(userId(authentication), accountId,
                periodType.toUpperCase(), from, to, granularity.toUpperCase(),
                calculationMode.toUpperCase(), traceId(request)), traceId(request));
    }

    @GetMapping("/analyses/attribution")
    public ApiResponse<Map<String, Object>> attribution(@PathVariable Long accountId,
                                                        @RequestParam(defaultValue = "ASSET") String dimension,
                                                        @RequestParam(defaultValue = "qmt") String source,
                                                        @RequestParam(required = false) String from,
                                                        @RequestParam(required = false) String to,
                                                        Authentication authentication, HttpServletRequest request) {
        return ApiResponse.success(analysisService.attribution(userId(authentication), accountId,
                dimension.toUpperCase(), source.toLowerCase(), from, to, traceId(request)), traceId(request));
    }

    private Long userId(Authentication authentication) { return Long.valueOf(authentication.getName()); }
    private String traceId(HttpServletRequest request) { return String.valueOf(request.getAttribute("traceId")); }
}
