package com.stockmanager.analysis.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stockmanager.account.entity.Account;
import com.stockmanager.account.entity.Position;
import com.stockmanager.account.mapper.AccountMapper;
import com.stockmanager.account.mapper.PositionMapper;
import com.stockmanager.analysis.service.AnalysisService;
import com.stockmanager.analysis.service.AccountSnapshotHistoryService;
import com.stockmanager.common.exception.BusinessException;
import com.stockmanager.integration.quant.QuantClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AnalysisServiceImpl implements AnalysisService {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final AccountMapper accountMapper;
    private final PositionMapper positionMapper;
    private final AccountSnapshotHistoryService snapshotHistoryService;
    private final QuantClient quantClient;

    public AnalysisServiceImpl(AccountMapper accountMapper, PositionMapper positionMapper,
                               AccountSnapshotHistoryService snapshotHistoryService,
                               QuantClient quantClient) {
        this.accountMapper = accountMapper;
        this.positionMapper = positionMapper;
        this.snapshotHistoryService = snapshotHistoryService;
        this.quantClient = quantClient;
    }

    @Override
    public Map<String, Object> allocation(Long userId, Long accountId, String dimension, String source) {
        Account account = requireAccount(userId, accountId);
        List<Position> positions = positions(accountId);
        List<Map<String, Object>> positionRows = positionRows(positions);
        String dataSource = ("mysql".equalsIgnoreCase(source) || "mongodb".equalsIgnoreCase(source))
                ? "mysql_current" : qmtSource(account);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "REGION".equals(dimension) || "MARKET".equals(dimension) ? "region" : "asset");
        result.put("analysisType", "ALLOCATION");
        result.put("dimension", dimension);
        result.put("total_market_value", money(zero(account.getMarketValue())));
        result.put("totalMarketValue", money(zero(account.getMarketValue())));
        result.put("positions", positionRows);
        result.put("asset_data", positionRows);
        result.put("items", groupedAllocation(account, positions, dimension));
        result.put("region_data", regionRows(positions));
        result.put("data_source", dataSource);
        result.put("source", dataSource);
        result.put("snapshot_time", account.getLastSyncTime());
        result.put("dataTime", account.getLastSyncTime());
        result.put("is_real_data", "qmt_live".equals(dataSource));
        result.put("stale", false);
        return result;
    }

    @Override
    public Map<String, Object> periodComparison(Long userId, Long accountId, String periodType,
                                                String from, String to, String granularity,
                                                String calculationMode, String traceId) {
        Account account = requireAccount(userId, accountId);
        LocalDate end = parseDate(to, LocalDate.now());
        int defaultDays = "WEEK".equals(periodType) ? 7 : 365;
        LocalDate start = parseDate(from, end.minusDays(defaultDays));
        boolean simulatedReturn = "SIMULATED".equalsIgnoreCase(calculationMode);
        Map<String, Object> history = simulatedReturn
                ? historicalPositionReplay(account, start, end, traceId)
                : portfolioHistory(account, start, end, granularity);
        List<Map<String, Object>> points = mapList(history.get("portfolioValues"));
        BigDecimal initial = points.isEmpty() ? BigDecimal.ZERO : decimal(points.getFirst().get("value"));
        BigDecimal simulationBase = initial;
        List<Map<String, Object>> yearlyData = new ArrayList<>();
        for (Map<String, Object> point : points) {
            BigDecimal value = decimal(point.get("value"));
            Map<String, Object> row = new LinkedHashMap<>();
            String date = String.valueOf(point.get("date"));
            row.put("year", date);
            row.put("timePeriod", date);
            row.put("totalAssets", money(value));
            row.put("returnRate", simulatedReturn
                    ? percentage(value.subtract(simulationBase), simulationBase)
                    : percentage(value.subtract(initial), initial));
            row.put("investmentRate", percentage(zero(account.getMarketValue()), zero(account.getTotalAsset())));
            yearlyData.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "time");
        result.put("yearly_data", yearlyData);
        result.put("data_source", history.getOrDefault("source", "qmt_history"));
        result.put("snapshot_time", account.getLastSyncTime());
        result.put("latest_time", account.getLastSyncTime());
        result.put("range_start", history.getOrDefault("rangeStart", start.toString()));
        result.put("range_end", history.getOrDefault("rangeEnd", end.toString()));
        result.put("sample_count", history.getOrDefault("tradingDays", yearlyData.size()));
        result.put("warnings", history.getOrDefault("warnings", List.of()));
        result.put("calculation_method", history.getOrDefault("calculationMethod", ""));
        result.put("return_calculation_mode", simulatedReturn ? "SIMULATED" : "SNAPSHOT");
        result.put("return_calculation_description", simulatedReturn
                ? "模拟收益率：固定当前持仓数量和现金，回放QMT前复权日线价格计算过去一年组合表现"
                : "实际快照收益率：按所选区间第一条账户快照作为基准");
        return result;
    }

    /**
     * 固定当前账户持仓和现金，调用 Python/QMT 历史行情服务回放日线价格。
     * 这不是券商历史净值，而是“当前组合穿越过去一年”的模拟表现。
     */
    private Map<String, Object> historicalPositionReplay(Account account, LocalDate start,
                                                         LocalDate end, String traceId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("accountId", text(account.getAccountNo(), String.valueOf(account.getId())));
        request.put("startDate", start.toString());
        request.put("endDate", end.toString());
        request.put("cash", zero(account.getCash()));
        List<Map<String, Object>> positionPayload = new ArrayList<>();
        for (Position position : positions(account.getId())) {
            if (position.getSecurityCode() == null || position.getSecurityCode().isBlank()
                    || zero(position.getQuantity()).signum() <= 0) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("symbol", position.getSecurityCode());
            item.put("securityName", text(position.getSecurityName(), position.getSecurityCode()));
            item.put("quantity", zero(position.getQuantity()));
            item.put("costPrice", zero(position.getCostPrice()));
            item.put("lastPrice", zero(position.getLastPrice()));
            item.put("marketValue", zero(position.getMarketValue()));
            item.put("profitLoss", zero(position.getProfitLoss()));
            item.put("industry", text(position.getIndustry(), ""));
            item.put("region", text(position.getRegion(), ""));
            positionPayload.add(item);
        }
        request.put("positions", positionPayload);
        return quantClient.portfolioHistory(request, traceId);
    }

    @Override
    public Map<String, Object> attribution(Long userId, Long accountId, String dimension,
                                           String source, String from, String to, String traceId) {
        Account account = requireAccount(userId, accountId);
        LocalDate end = parseDate(to, LocalDate.now());
        LocalDate start = parseDate(from, end.minusDays(30));
        boolean qmtCurrent = "QMT".equalsIgnoreCase(source) || "QMT_LIVE".equalsIgnoreCase(source);
        Map<String, Object> history = qmtCurrent
                ? currentPositionAttribution(account)
                : portfolioHistory(account, start, end, "DAILY");
        List<Map<String, Object>> rawPositions = mapList(history.get("positionHistory"));
        BigDecimal totalMarketValue = rawPositions.stream()
                .map(item -> decimal(item.get("marketValue")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, IndustryBucket> industries = new LinkedHashMap<>();
        BigDecimal totalPnl = BigDecimal.ZERO;
        int positive = 0;
        int negative = 0;
        for (Map<String, Object> raw : rawPositions) {
            BigDecimal marketValue = decimal(raw.get("marketValue"));
            BigDecimal pnl = decimal(raw.get("periodPnl"));
            BigDecimal startPrice = decimal(raw.get("startPrice"));
            BigDecimal endPrice = decimal(raw.get("endPrice"));
            String industry = text(raw.get("industry"), "其他");
            BigDecimal contribution = ratio(pnl, totalMarketValue);
            BigDecimal returnRate = ratio(endPrice.subtract(startPrice), startPrice);
            if (contribution.signum() > 0) positive++;
            if (contribution.signum() < 0) negative++;
            totalPnl = totalPnl.add(pnl);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stockCode", text(raw.get("symbol"), ""));
            row.put("stockName", text(raw.get("securityName"), text(raw.get("symbol"), "")));
            row.put("industry", industry);
            row.put("weightPct", number(ratio(marketValue, totalMarketValue)));
            row.put("contributionPct", number(contribution));
            row.put("returnRate", number(returnRate));
            row.put("pnlAmount", number(pnl));
            row.put("marketValue", number(marketValue));
            row.put("currentPrice", number(endPrice));
            row.put("startPrice", number(startPrice));
            row.put("costPrice", number(decimal(raw.get("costPrice"))));
            row.put("volume", number(decimal(raw.get("quantity"))));
            rows.add(row);
            industries.computeIfAbsent(industry, ignored -> new IndustryBucket())
                    .add(contribution, pnl, marketValue);
        }
        rows.sort(Comparator.comparing((Map<String, Object> row) ->
                decimal(row.get("contributionPct")).abs()).reversed());

        List<Map<String, Object>> industryRows = industries.entrySet().stream().map(entry -> {
            IndustryBucket bucket = entry.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", entry.getKey());
            row.put("contributionPct", number(bucket.contribution));
            row.put("pnlAmount", number(bucket.pnl));
            row.put("marketValue", number(bucket.marketValue));
            row.put("count", bucket.count);
            return row;
        }).sorted(Comparator.comparing((Map<String, Object> row) ->
                decimal(row.get("contributionPct")).abs()).reversed()).toList();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalMarketValue", number(totalMarketValue));
        summary.put("totalPnlAmount", number(totalPnl));
        summary.put("positiveCount", positive);
        summary.put("negativeCount", negative);
        summary.put("leadingIndustry", industryRows.isEmpty() ? "--" : industryRows.getFirst().get("name"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("dimension", dimension);
        result.put("summary", summary);
        result.put("attributionRows", rows);
        result.put("industryRows", industryRows);
        result.put("data_source", history.getOrDefault("source", "qmt_history"));
        result.put("snapshot_time", account.getLastSyncTime());
        result.put("range_start", history.getOrDefault("rangeStart", start.toString()));
        result.put("range_end", history.getOrDefault("rangeEnd", end.toString()));
        result.put("sample_count", history.getOrDefault("tradingDays", 0));
        result.put("warnings", history.getOrDefault("warnings", List.of()));
        result.put("calculation_method", history.getOrDefault("calculationMethod", ""));
        return result;
    }

    /** QMT口径：直接使用最近一次同步的当前持仓盈亏，保留正负号。 */
    private Map<String, Object> currentPositionAttribution(Account account) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Position position : positions(account.getId())) {
            BigDecimal quantity = zero(position.getQuantity());
            BigDecimal cost = zero(position.getCostPrice());
            BigDecimal last = zero(position.getLastPrice());
            BigDecimal marketValue = zero(position.getMarketValue());
            BigDecimal pnl = zero(position.getProfitLoss());
            if (position.getSecurityCode() == null || position.getSecurityCode().isBlank()
                    || quantity.signum() <= 0) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("symbol", position.getSecurityCode());
            row.put("securityName", text(position.getSecurityName(), position.getSecurityCode()));
            row.put("quantity", quantity.toPlainString());
            row.put("costPrice", cost.toPlainString());
            row.put("startPrice", cost.toPlainString());
            row.put("endPrice", last.toPlainString());
            row.put("marketValue", money(marketValue));
            row.put("periodPnl", money(pnl));
            row.put("profitLoss", money(pnl));
            row.put("industry", industry(position));
            row.put("region", region(position));
            rows.add(row);
        }
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("positionHistory", rows);
        history.put("source", "qmt_live");
        history.put("tradingDays", 1);
        history.put("rangeStart", account.getLastSyncTime() == null ? "" : account.getLastSyncTime().toLocalDate().toString());
        history.put("rangeEnd", history.get("rangeStart"));
        history.put("calculationMethod", "QMT最近同步持仓的当前盈亏（成本价与最新价）");
        history.put("warnings", List.of("QMT口径使用最近一次同步的当前持仓盈亏，不代表所选日期区间内的历史净值贡献"));
        return history;
    }

    private List<Map<String, Object>> positionRows(List<Position> positions) {
        BigDecimal total = positions.stream().map(Position::getMarketValue).map(this::zero)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return positions.stream().sorted(Comparator.comparing((Position p) -> zero(p.getMarketValue())).reversed())
                .map(position -> {
                    BigDecimal cost = zero(position.getCostPrice());
                    BigDecimal last = zero(position.getLastPrice());
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("stock_code", position.getSecurityCode());
                    row.put("stock_name", position.getSecurityName());
                    row.put("volume", number(zero(position.getQuantity())));
                    row.put("available_volume", number(zero(position.getAvailableQuantity())));
                    row.put("current_price", number(last));
                    row.put("cost_price", number(cost));
                    row.put("market_value", number(zero(position.getMarketValue())));
                    row.put("profit_loss", number(zero(position.getProfitLoss())));
                    row.put("asset_ratio", number(ratio(zero(position.getMarketValue()), total)));
                    row.put("percentage", number(ratio(zero(position.getMarketValue()), total)));
                    row.put("daily_return", number(ratio(last.subtract(cost), cost)));
                    row.put("profit_loss_rate", number(ratio(last.subtract(cost), cost)));
                    row.put("industry", industry(position));
                    row.put("region", region(position));
                    return row;
                }).toList();
    }

    private List<Map<String, Object>> groupedAllocation(Account account, List<Position> positions, String dimension) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        Map<String, BigDecimal> profits = new LinkedHashMap<>();
        for (Position position : positions) {
            String key = switch (dimension) {
                case "INDUSTRY" -> industry(position);
                case "REGION", "MARKET" -> region(position);
                default -> "股票";
            };
            values.merge(key, zero(position.getMarketValue()), BigDecimal::add);
            profits.merge(key, zero(position.getProfitLoss()), BigDecimal::add);
        }
        if ("ASSET_CLASS".equals(dimension)) values.put("现金", zero(account.getCash()));
        BigDecimal total = values.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return values.entrySet().stream().map(entry -> {
            BigDecimal value = entry.getValue();
            BigDecimal profit = profits.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", entry.getKey().toUpperCase());
            row.put("name", entry.getKey());
            row.put("marketValue", money(value));
            row.put("weight", percentage(value, total));
            row.put("profitLoss", money(profit));
            row.put("profitLossRate", percentage(profit, value.subtract(profit)));
            return row;
        }).toList();
    }

    private List<Map<String, Object>> regionRows(List<Position> positions) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        Map<String, BigDecimal> costs = new LinkedHashMap<>();
        for (Position position : positions) {
            String region = region(position);
            values.merge(region, zero(position.getMarketValue()), BigDecimal::add);
            costs.merge(region, zero(position.getCostPrice()).multiply(zero(position.getQuantity())), BigDecimal::add);
        }
        BigDecimal total = values.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return values.entrySet().stream().map(entry -> {
            BigDecimal cost = costs.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("region", entry.getKey());
            row.put("totalAssets", number(entry.getValue()));
            row.put("returnRate", number(ratio(entry.getValue().subtract(cost), cost)));
            row.put("investmentRate", number(ratio(entry.getValue(), total)));
            return row;
        }).sorted(Comparator.comparing((Map<String, Object> row) -> decimal(row.get("totalAssets"))).reversed()).toList();
    }

    private Map<String, Object> portfolioHistory(Account account, LocalDate start, LocalDate end,
                                                 String granularity) {
        return snapshotHistoryService.portfolioHistory(account.getId(), start, end, granularity);
    }

    private Account requireAccount(Long userId, Long accountId) {
        Account account = accountMapper.selectOne(Wrappers.<Account>lambdaQuery()
                .eq(Account::getId, accountId).eq(Account::getUserId, userId));
        if (account == null) throw new BusinessException(404101, "账户不存在", HttpStatus.NOT_FOUND);
        return account;
    }

    private List<Position> positions(Long accountId) {
        return positionMapper.selectList(Wrappers.<Position>lambdaQuery().eq(Position::getAccountId, accountId));
    }

    private String qmtSource(Account account) {
        return "GUOJIN_QMT".equalsIgnoreCase(account.getBroker()) ? "qmt_live" : "mysql";
    }

    private String industry(Position position) {
        return blank(position.getIndustry(), inferIndustry(position.getSecurityName(), position.getSecurityCode()));
    }

    private String inferIndustry(String nameValue, String codeValue) {
        String name = blank(nameValue, "");
        String code = blank(codeValue, "");
        if (name.toUpperCase().contains("ETF") || code.matches("^(15|16|50|51|52|56|58).*")) return "宽基ETF";
        if (contains(name, "银行")) return "银行";
        if (contains(name, "茅台", "酒", "食品", "饮料")) return "食品饮料";
        if (contains(name, "机场", "国航", "顺丰", "航空", "铁路")) return "交通运输";
        if (contains(name, "电力", "能源", "三峡")) return "公用事业";
        if (contains(name, "移动", "通信", "有线")) return "通信";
        if (contains(name, "制药", "医疗", "医药")) return "医药生物";
        if (contains(name, "宁德", "电池", "光伏")) return "电力设备";
        if (contains(name, "寒武纪", "立讯", "电子", "芯片")) return "电子";
        if (contains(name, "科技", "汉王", "软件")) return "计算机";
        if (contains(name, "建材", "建设")) return "建筑材料";
        if (contains(name, "王府井", "商贸")) return "商贸零售";
        if (contains(name, "汽车", "东风")) return "汽车";
        if (contains(name, "地产", "大悦城")) return "房地产";
        if (contains(name, "纺织", "服饰", "安妮", "华升")) return "纺织服饰";
        return "其他";
    }

    private boolean contains(String value, String... fragments) {
        for (String fragment : fragments) if (value.contains(fragment)) return true;
        return false;
    }

    private String region(Position position) {
        if (position.getRegion() != null && !position.getRegion().isBlank()) return position.getRegion();
        String code = blank(position.getSecurityCode(), "").toUpperCase();
        if (code.endsWith(".SZ")) return "深圳市场";
        if (code.endsWith(".BJ")) return "北京市场";
        if (code.endsWith(".SH")) return "上海市场";
        return "其他市场";
    }

    private LocalDate parseDate(String value, LocalDate fallback) {
        try { return value == null || value.isBlank() ? fallback : LocalDate.parse(value); }
        catch (Exception ignored) { return fallback; }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object value) {
        return value instanceof List<?> list ? list.stream()
                .filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList() : List.of();
    }

    private BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private BigDecimal decimal(Object value) {
        try { return new BigDecimal(String.valueOf(value)); } catch (Exception ignored) { return BigDecimal.ZERO; }
    }
    private BigDecimal ratio(BigDecimal value, BigDecimal total) {
        return total.signum() == 0 ? BigDecimal.ZERO : value.multiply(HUNDRED).divide(total, 6, RoundingMode.HALF_UP);
    }
    private String money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP).toPlainString(); }
    private String percentage(BigDecimal value, BigDecimal total) { return ratio(value, total).setScale(2, RoundingMode.HALF_UP).toPlainString(); }
    private BigDecimal number(BigDecimal value) { return value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros(); }
    private String text(Object value, String fallback) { return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value); }
    private String blank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }

    private static final class IndustryBucket {
        private BigDecimal contribution = BigDecimal.ZERO;
        private BigDecimal pnl = BigDecimal.ZERO;
        private BigDecimal marketValue = BigDecimal.ZERO;
        private int count;

        private void add(BigDecimal contributionValue, BigDecimal pnlValue, BigDecimal marketValueValue) {
            contribution = contribution.add(contributionValue);
            pnl = pnl.add(pnlValue);
            marketValue = marketValue.add(marketValueValue);
            count++;
        }
    }
}
