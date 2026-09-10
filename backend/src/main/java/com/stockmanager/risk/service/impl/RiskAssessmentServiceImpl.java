package com.stockmanager.risk.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stockmanager.account.entity.Account;
import com.stockmanager.account.entity.Position;
import com.stockmanager.account.mapper.AccountMapper;
import com.stockmanager.account.mapper.PositionMapper;
import com.stockmanager.analysis.service.AccountSnapshotHistoryService;
import com.stockmanager.common.exception.BusinessException;
import com.stockmanager.integration.quant.QuantClient;
import com.stockmanager.risk.service.RiskAssessmentPersistenceService;
import com.stockmanager.risk.service.RiskAssessmentService;
import com.stockmanager.risk.vo.RiskAssessmentView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 风险评估实现，负责准备历史组合样本、调用 FastAPI 统计引擎、执行阈值分级并持久化结果。
 *
 * <p>波动率、最大回撤和 VaR 必须基于时间序列，因此本类读取 MySQL 历史快照，而不是仅使用
 * Redis 中的当前账户快照。FastAPI 负责数值计算，Spring 负责账户权限、业务阈值、风险等级、
 * 版本信息和审计数据落库。</p>
 */
@Service
public class RiskAssessmentServiceImpl implements RiskAssessmentService {
    private final AccountMapper accountMapper;
    private final PositionMapper positionMapper;
    private final QuantClient quantClient;
    private final RiskAssessmentPersistenceService persistenceService;
    private final AccountSnapshotHistoryService snapshotHistoryService;

    /** 注入账户历史读取、FastAPI 计算和风险结果持久化所需依赖。 */
    public RiskAssessmentServiceImpl(AccountMapper accountMapper, PositionMapper positionMapper,
                                     QuantClient quantClient, RiskAssessmentPersistenceService persistenceService,
                                     AccountSnapshotHistoryService snapshotHistoryService) {
        this.accountMapper = accountMapper;
        this.positionMapper = positionMapper;
        this.quantClient = quantClient;
        this.persistenceService = persistenceService;
        this.snapshotHistoryService = snapshotHistoryService;
    }

    /** 按最近若干天构造默认日频区间并执行风险计算。 */
    @Override
    public RiskAssessmentView calculate(Long userId, Long accountId, int days, String traceId) {
        // 至少回看两天，避免调用方传0/负数后构造空收益率序列。
        LocalDate end = LocalDate.now();
        return calculateForRange(userId, accountId, end.minusDays(Math.max(days, 2)), end, "DAILY", traceId);
    }

    /**
     * 按前端选择的日期和粒度执行风险计算；日期反向时自动交换起止值。
     */
    @Override
    public RiskAssessmentView latestOrCalculate(Long userId, Long accountId, int days,
                                                String startDate, String endDate, String granularity, String traceId) {
        // 非法/空日期使用业务默认值；反向区间在下方交换，而不是直接报错中断页面。
        LocalDate end = parseDate(endDate, LocalDate.now());
        LocalDate start = parseDate(startDate, end.minusDays(Math.max(days, 2)));
        if (start.isAfter(end)) {
            LocalDate swap = start;
            start = end;
            end = swap;
        }
        return calculateForRange(userId, accountId, start, end, granularity, traceId);
    }

    /**
     * 风险计算主流程：读取样本、校验有效交易日、调用量化服务、分级并保存结果。
     */
    private RiskAssessmentView calculateForRange(Long userId, Long accountId, LocalDate start,
                                                  LocalDate end, String granularity, String traceId) {
        /*
         * 风险计算不直接读取当前 Redis 快照，而是读取 MySQL 的历史快照：
         * 当前快照适合展示“现在”，历史序列才足以计算波动率、回撤和 VaR。
         * 因此本方法先恢复统一的 portfolioValues/positionHistory，再把它们注入 Python 风险服务。
         */
        // 在任何历史读取和FastAPI调用前校验账户归属，避免越权计算造成信息泄漏和资源消耗。
        Account account = requireAccount(userId, accountId);
        String mode = "ALL".equalsIgnoreCase(granularity) ? "ALL" : "DAILY";
        Map<String, Object> history = snapshotHistoryService.portfolioHistory(accountId, start, end, mode);
        List<Map<String, Object>> values = mapList(history.get("portfolioValues"));
        // 没有至少两个有效时间点就没有收益率序列，继续计算会得到没有统计意义的结果。
        if (values.size() < 2) {
            throw new BusinessException(422401, "所选区间有效交易日不足2天，无法计算风险指标",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        // 把数据范围和粒度编码进版本号，便于审计时解释“这次指标到底基于哪批数据”。
        String dataVersion = "mysql-snapshot-" + mode.toLowerCase() + "-" + accountId + "-" + start + "-" + end;
        // Spring只传标准化净值序列和计算参数，Python不读取数据库，因此数值计算容易测试和复现。
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

        // FastAPI 负责统计计算；Spring 负责阈值分级、统一返回结构和结果持久化。
        // rawMetrics是纯数值结果，availability说明样本是否足够；业务阈值仍由Spring掌控。
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
        // 风险评分是可解释的规则加权，不把 Python 返回的单一数值直接当作最终等级。
        int score = riskScore(maxLoss, volatility, drawdown, var);
        String level = score >= 60 ? "HIGH" : score >= 30 ? "MEDIUM" : "LOW";
        String recommendation = switch (level) {
            case "HIGH" -> "当前风险偏高，建议降低仓位或增加防御配置。";
            case "MEDIUM" -> "当前风险处于中等水平，建议关注波动放大与回撤扩张。";
            default -> "当前风险较低，可继续维持现有仓位结构。";
        };

        // 完整保存输入版本、规则版本、算法版本、环境、来源和警告，支持事后解释同一账户的历史结果差异。
        return persistenceService.save(accountId, start, end, level, BigDecimal.valueOf(score), metrics,
                List.of(recommendation), castMap(result.getOrDefault("sample", Map.of())), dataVersion, 1,
                "risk-v2.0.0", account.getEnvironment(),
                String.valueOf(history.getOrDefault("source", "mysql_account_snapshots")),
                stringList(history.get("warnings")), LocalDateTime.now());
    }

    /** 按账户与用户联合查询，确保风险结果只能针对本人账户计算。 */
    private Account requireAccount(Long userId, Long accountId) {
        Account account = accountMapper.selectOne(Wrappers.<Account>lambdaQuery()
                .eq(Account::getId, accountId).eq(Account::getUserId, userId));
        if (account == null) throw new BusinessException(404101, "账户不存在", HttpStatus.NOT_FOUND);
        return account;
    }

    /** 把单个指标转换为包含阈值、状态和可用性的前端展示结构。 */
    private Map<String, Object> metric(Object value, double warning, double danger, boolean available) {
        // 数值达到danger标红，达到warning标黄，其余正常；不可用标记与颜色状态分别表达。
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

    /** 使用四项风险指标的分段得分计算总分，最高 100 分。 */
    private int riskScore(double maxLoss, double volatility, double drawdown, double var) {
        return points(maxLoss, 5, 10, 20) + points(volatility, 10, 20, 30)
                + points(drawdown, 10, 20, 30) + points(var, 2, 3, 5);
    }

    /** 将单项指标映射为 0、5、15 或 25 分。 */
    private int points(double value, double low, double medium, double high) {
        if (value > high) return 25;
        if (value > medium) return 15;
        if (value > low) return 5;
        return 0;
    }

    /** 读取量化服务的指标可用性；旧响应缺失该字段时按可用兼容。 */
    private boolean available(Map<String, Object> availability, String key) {
        return !availability.containsKey(key) || Boolean.TRUE.equals(availability.get(key));
    }

    /** 解析 ISO 日期，非法值使用调用方默认值。 */
    private LocalDate parseDate(String value, LocalDate fallback) {
        try { return value == null || value.isBlank() ? fallback : LocalDate.parse(value); }
        catch (Exception ignored) { return fallback; }
    }

    /** 将可空 BigDecimal 转为接口使用的十进制字符串。 */
    private String number(BigDecimal value) { return value == null ? "0" : value.toPlainString(); }
    /** 将弱类型量化结果转为 double；异常值按零处理并由 availability 说明可信度。 */
    private double parse(Object value) {
        try { return Double.parseDouble(String.valueOf(value)); } catch (Exception ignored) { return 0; }
    }

    /** 将上游对象安全转换为字符串键 Map。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    /** 从上游结果中提取对象列表。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object value) {
        return value instanceof List<?> list ? list.stream()
                .filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList() : List.of();
    }

    /** 把上游警告集合转换为稳定的字符串列表。 */
    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : list) result.add(String.valueOf(item));
        return result;
    }
}
