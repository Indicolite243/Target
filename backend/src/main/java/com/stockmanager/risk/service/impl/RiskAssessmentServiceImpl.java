package com.stockmanager.risk.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stockmanager.account.entity.Account;
import com.stockmanager.account.entity.Position;
import com.stockmanager.account.mapper.AccountMapper;
import com.stockmanager.account.mapper.PositionMapper;
import com.stockmanager.analysis.service.AccountSnapshotHistoryService;
import com.stockmanager.common.exception.BusinessException;
import com.stockmanager.integration.quant.QuantClient;
import com.stockmanager.risk.document.RiskAssessment;
import com.stockmanager.risk.repository.RiskAssessmentRepository;
import com.stockmanager.risk.service.RiskAssessmentService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RiskAssessmentServiceImpl implements RiskAssessmentService {
    private final AccountMapper accountMapper;
    private final PositionMapper positionMapper;
    private final QuantClient quantClient;
    private final RiskAssessmentRepository repository;
    private final AccountSnapshotHistoryService snapshotHistoryService;

    public RiskAssessmentServiceImpl(AccountMapper accountMapper, PositionMapper positionMapper,
                                     QuantClient quantClient, RiskAssessmentRepository repository,
                                     AccountSnapshotHistoryService snapshotHistoryService) {
        this.accountMapper = accountMapper;
        this.positionMapper = positionMapper;
        this.quantClient = quantClient;
        this.repository = repository;
        this.snapshotHistoryService = snapshotHistoryService;
    }

    @Override
    public RiskAssessment calculate(Long userId, Long accountId, int days, String traceId) {
        LocalDate end = LocalDate.now();
        return calculateForRange(userId, accountId, end.minusDays(Math.max(days, 2)), end, "DAILY", traceId);
    }

    @Override
    public RiskAssessment latestOrCalculate(Long userId, Long accountId, int days,
                                            String startDate, String endDate, String granularity, String traceId) {
        LocalDate end = parseDate(endDate, LocalDate.now());
        LocalDate start = parseDate(startDate, end.minusDays(Math.max(days, 2)));
        if (start.isAfter(end)) {
            LocalDate swap = start;
            start = end;
            end = swap;
        }
        return calculateForRange(userId, accountId, start, end, granularity, traceId);
    }

    private RiskAssessment calculateForRange(Long userId, Long accountId, LocalDate start,
                                             LocalDate end, String granularity, String traceId) {
        Account account = requireAccount(userId, accountId);
        String mode = "ALL".equalsIgnoreCase(granularity) ? "ALL" : "DAILY";
        Map<String, Object> history = snapshotHistoryService.portfolioHistory(accountId, start, end, mode);
        List<Map<String, Object>> values = mapList(history.get("portfolioValues"));
        if (values.size() < 2) {
            throw new BusinessException(422401, "所选区间有效交易日不足2天，无法计算风险指标",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        String dataVersion = "mongodb-snapshot-" + mode.toLowerCase() + "-" + accountId + "-" + start + "-" + end;
        Map<String, Object> result = quantClient.calculateRisk(Map.of(
                "accountId", String.valueOf(accountId),
                "dataVersion", dataVersion,
                "ruleVersion", 1,
                "algorithmVersion", "risk-v2.0.0",
                "confidenceLevel", "0.95",
                "holdingPeriodDays", 1,
                "portfolioValues", values,
                "positions", history.getOrDefault("positionHistory", List.of()),
                "thresholds", Map.of("maxPrincipalLossRate", "10", "annualVolatilityRate", "20",
                        "maxDrawdownRate", "15", "varRate", "3")
        ), traceId);

        Map<String, Object> rawMetrics = castMap(result.get("metrics"));
        Map<String, Object> availability = castMap(result.get("availability"));
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("maxPrincipalLoss", metric(rawMetrics.get("maxPrincipalLossRate"), 10, 20,
                available(availability, "maxPrincipalLoss")));
        metrics.put("volatility", metric(rawMetrics.get("annualVolatilityRate"), 20, 30,
                available(availability, "volatility")));
        metrics.put("maxDrawdown", metric(rawMetrics.get("maxDrawdownRate"), 15, 25,
                available(availability, "maxDrawdown")));
        metrics.put("var", metric(rawMetrics.get("varRate"), 3, 5,
                available(availability, "var")));

        double maxLoss = parse(rawMetrics.get("maxPrincipalLossRate"));
        double volatility = parse(rawMetrics.get("annualVolatilityRate"));
        double drawdown = parse(rawMetrics.get("maxDrawdownRate"));
        double var = parse(rawMetrics.get("varRate"));
        int score = riskScore(maxLoss, volatility, drawdown, var);
        String level = score >= 60 ? "HIGH" : score >= 30 ? "MEDIUM" : "LOW";
        String recommendation = switch (level) {
            case "HIGH" -> "当前风险偏高，建议降低仓位或增加防御配置。";
            case "MEDIUM" -> "当前风险处于中等水平，建议关注波动放大与回撤扩张。";
            default -> "当前风险较低，可继续维持现有仓位结构。";
        };

        RiskAssessment assessment = new RiskAssessment();
        assessment.setAccountId(accountId);
        assessment.setRiskScore(String.valueOf(score));
        assessment.setRiskLevel(level);
        assessment.setMetrics(metrics);
        assessment.setRecommendations(List.of(recommendation));
        assessment.setSample(castMap(result.getOrDefault("sample", Map.of())));
        assessment.setDataVersion(dataVersion);
        assessment.setRuleVersion(1);
        assessment.setAlgorithmVersion("risk-v2.0.0");
        assessment.setEnvironment(account.getEnvironment());
        assessment.setDataSource(String.valueOf(history.getOrDefault("source", "qmt_history")));
        assessment.setWarnings(stringList(history.get("warnings")));
        assessment.setCalculatedAt(LocalDateTime.now());
        return repository.save(assessment);
    }

    private Account requireAccount(Long userId, Long accountId) {
        Account account = accountMapper.selectOne(Wrappers.<Account>lambdaQuery()
                .eq(Account::getId, accountId).eq(Account::getUserId, userId));
        if (account == null) throw new BusinessException(404101, "账户不存在", HttpStatus.NOT_FOUND);
        return account;
    }

    private Map<String, Object> metric(Object value, double warning, double danger, boolean available) {
        double number = parse(value);
        String status = number >= danger ? "DANGER" : number >= warning ? "WARNING" : "NORMAL";
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("value", String.format("%.2f", number));
        metric.put("unit", "%");
        metric.put("threshold", String.format("%.2f", warning));
        metric.put("dangerThreshold", String.format("%.2f", danger));
        metric.put("status", status);
        metric.put("available", available);
        return metric;
    }

    private int riskScore(double maxLoss, double volatility, double drawdown, double var) {
        return points(maxLoss, 5, 10, 20) + points(volatility, 10, 20, 30)
                + points(drawdown, 10, 20, 30) + points(var, 2, 3, 5);
    }

    private int points(double value, double low, double medium, double high) {
        if (value > high) return 25;
        if (value > medium) return 15;
        if (value > low) return 5;
        return 0;
    }

    private boolean available(Map<String, Object> availability, String key) {
        return !availability.containsKey(key) || Boolean.TRUE.equals(availability.get(key));
    }

    private LocalDate parseDate(String value, LocalDate fallback) {
        try { return value == null || value.isBlank() ? fallback : LocalDate.parse(value); }
        catch (Exception ignored) { return fallback; }
    }

    private String number(BigDecimal value) { return value == null ? "0" : value.toPlainString(); }
    private double parse(Object value) {
        try { return Double.parseDouble(String.valueOf(value)); } catch (Exception ignored) { return 0; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object value) {
        return value instanceof List<?> list ? list.stream()
                .filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList() : List.of();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : list) result.add(String.valueOf(item));
        return result;
    }
}
