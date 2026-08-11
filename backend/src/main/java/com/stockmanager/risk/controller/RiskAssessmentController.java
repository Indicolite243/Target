package com.stockmanager.risk.controller;

import com.stockmanager.common.response.ApiResponse;
import com.stockmanager.risk.document.RiskAssessment;
import com.stockmanager.risk.service.RiskAssessmentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}/risk-assessments")
public class RiskAssessmentController {
    private final RiskAssessmentService service;

    public RiskAssessmentController(RiskAssessmentService service) { this.service = service; }

    @GetMapping("/latest")
    public ApiResponse<RiskAssessment> latest(@PathVariable Long accountId,
                                              @RequestParam(defaultValue = "250") int days,
                                              @RequestParam(required = false) String startDate,
                                              @RequestParam(required = false) String endDate,
                                              @RequestParam(defaultValue = "DAILY") String granularity,
                                              Authentication authentication, HttpServletRequest request) {
        return ApiResponse.success(service.latestOrCalculate(userId(authentication), accountId, days,
                startDate, endDate, granularity.toUpperCase(), traceId(request)), traceId(request));
    }

    @PostMapping
    public ApiResponse<RiskAssessment> calculate(@PathVariable Long accountId,
                                                 @RequestBody(required = false) MapRequest body,
                                                 Authentication authentication, HttpServletRequest request) {
        int days = body == null || body.days() == null ? 250 : body.days();
        return ApiResponse.success("风险评估已完成", service.calculate(userId(authentication), accountId, days, traceId(request)), traceId(request));
    }

    private Long userId(Authentication authentication) { return Long.valueOf(authentication.getName()); }
    private String traceId(HttpServletRequest request) { return String.valueOf(request.getAttribute("traceId")); }
    public record MapRequest(Integer days) {}
}
