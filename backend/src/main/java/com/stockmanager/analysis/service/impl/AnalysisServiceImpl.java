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

/**
 * 账户分析/归因门面：负责选择数据口径、调用历史数据服务，并把 Python/QMT 的原始结果
 * 转成前端稳定的中文业务结构。这里不直接修改账户和持仓，只生成分析结果。
 */
@Service
public class AnalysisServiceImpl implements AnalysisService {
    /** 小数比率转百分数时使用的固定乘数，避免重复创建 BigDecimal。 */
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** 读取账户资产摘要并执行用户归属校验。 */
    private final AccountMapper accountMapper;
    /** 读取当前持仓，用于配置分布和 QMT 当前盈亏归因。 */
    private final PositionMapper positionMapper;
    /** 读取 MySQL 不可变历史快照，支持真实时间段对比和历史归因。 */
    private final AccountSnapshotHistoryService snapshotHistoryService;
    /** 请求 Python/QMT 历史行情，执行“固定当前持仓穿越历史”的模拟回放。 */
    private final QuantClient quantClient;

    /** 注入当前账户持仓、历史快照和量化服务访问依赖。 */
    public AnalysisServiceImpl(AccountMapper accountMapper, PositionMapper positionMapper,
                               AccountSnapshotHistoryService snapshotHistoryService,
                               QuantClient quantClient) {
        this.accountMapper = accountMapper;
        this.positionMapper = positionMapper;
        this.snapshotHistoryService = snapshotHistoryService;
        this.quantClient = quantClient;
    }

    /**
     * 计算当前账户的资产配置分布。
     *
     * <p>该接口只使用当前账户/持仓事实，不回放历史行情。返回结构同时保留新旧前端字段名，
     * 便于改造期间平滑迁移。</p>
     */
    @Override
    public Map<String, Object> allocation(Long userId, Long accountId, String dimension, String source) {
        // 资产配置是当前快照分析：持仓来自 MySQL 当前表，source 只用于标识数据来源口径。
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

    /**
     * 计算时间区间对比，可选择真实 MySQL 快照收益或固定当前持仓的历史行情模拟收益。
     */
    @Override
    public Map<String, Object> periodComparison(Long userId, Long accountId, String periodType,
                                                String from, String to, String granularity,
                                                String calculationMode, String traceId) {
        /*
         * 两种收益口径必须明确区分：
         * SNAPSHOT  = 读取区间内实际历史账户快照；
         * SIMULATED = 固定当前持仓/现金，调用量化服务回放历史行情。
         * 前端同时展示结果和 calculation_method，避免把“模拟穿越收益”误认为真实净值。
         */
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
        // 只把当前仍持有的正数量仓位注入 Python；空仓位会造成无意义的价格请求和噪声。
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

    /**
     * 计算证券和行业维度的收益贡献。
     *
     * <p>当前 QMT 口径使用最近一次同步的持仓盈亏；历史口径使用 MySQL 中的持仓快照序列。
     * 两种口径在返回值中明确携带来源和计算方法，避免前端误解。</p>
     */
    @Override
    public Map<String, Object> attribution(Long userId, Long accountId, String dimension,
                                           String source, String from, String to, String traceId) {
        // QMT 口径取当前持仓盈亏；历史口径取每日历史持仓，再按标的和行业聚合贡献。
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
            // contribution 以组合总市值为分母，returnRate 以该标的起始价为分母；两者含义不同。
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

    /**
     * 将当前持仓转换为资产配置表行，并计算权重、持仓收益率等派生字段。
     */
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

    /** 按资产类别、行业或交易市场聚合市值与盈亏。 */
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

    /** 按交易市场聚合市值、成本和收益率，供分市场对比展示。 */
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

    /** 从 MySQL 历史快照服务读取统一的组合历史结构。 */
    private Map<String, Object> portfolioHistory(Account account, LocalDate start, LocalDate end,
                                                 String granularity) {
        return snapshotHistoryService.portfolioHistory(account.getId(), start, end, granularity);
    }

    /** 按账户和用户联合校验资源归属。 */
    private Account requireAccount(Long userId, Long accountId) {
        Account account = accountMapper.selectOne(Wrappers.<Account>lambdaQuery()
                .eq(Account::getId, accountId).eq(Account::getUserId, userId));
        if (account == null) throw new BusinessException(404101, "账户不存在", HttpStatus.NOT_FOUND);
        return account;
    }

    /** 读取账户当前持仓事实表。 */
    private List<Position> positions(Long accountId) {
        return positionMapper.selectList(Wrappers.<Position>lambdaQuery().eq(Position::getAccountId, accountId));
    }

    /** 根据券商类型生成可供前端识别的数据来源标记。 */
    private String qmtSource(Account account) {
        return "GUOJIN_QMT".equalsIgnoreCase(account.getBroker()) ? "qmt_live" : "mysql";
    }

    /** 优先使用持仓已保存行业，缺失时按证券名称和代码做展示级推断。 */
    private String industry(Position position) {
        return blank(position.getIndustry(), inferIndustry(position.getSecurityName(), position.getSecurityCode()));
    }

    /**
     * 为旧持仓补齐行业展示值。该规则仅用于页面分类，不属于正式证券主数据。
     */
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

    /** 判断文本是否包含任意一个候选片段。 */
    private boolean contains(String value, String... fragments) {
        for (String fragment : fragments) if (value.contains(fragment)) return true;
        return false;
    }

    /** 优先使用持仓市场字段，否则根据证券代码后缀推断交易市场。 */
    private String region(Position position) {
        if (position.getRegion() != null && !position.getRegion().isBlank()) return position.getRegion();
        String code = blank(position.getSecurityCode(), "").toUpperCase();
        if (code.endsWith(".SZ")) return "深圳市场";
        if (code.endsWith(".BJ")) return "北京市场";
        if (code.endsWith(".SH")) return "上海市场";
        return "其他市场";
    }

    /** 解析 ISO 日期；空值或非法值使用业务默认日期。 */
    private LocalDate parseDate(String value, LocalDate fallback) {
        try { return value == null || value.isBlank() ? fallback : LocalDate.parse(value); }
        catch (Exception ignored) { return fallback; }
    }

    /** 从弱类型响应中筛选 Map 列表，忽略无法识别的元素。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object value) {
        return value instanceof List<?> list ? list.stream()
                .filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList() : List.of();
    }

    /** 将可空金额归一化为零，简化后续 BigDecimal 运算。 */
    private BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    /** 将任意上游数值转换为 BigDecimal；非法值按零处理以保持展示接口可用。 */
    private BigDecimal decimal(Object value) {
        try { return new BigDecimal(String.valueOf(value)); } catch (Exception ignored) { return BigDecimal.ZERO; }
    }
    /** 计算百分比值，分母为零时返回零。 */
    private BigDecimal ratio(BigDecimal value, BigDecimal total) {
        return total.signum() == 0 ? BigDecimal.ZERO : value.multiply(HUNDRED).divide(total, 6, RoundingMode.HALF_UP);
    }
    /** 金额统一保留两位小数。 */
    private String money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP).toPlainString(); }
    /** 计算并格式化两位百分比。 */
    private String percentage(BigDecimal value, BigDecimal total) { return ratio(value, total).setScale(2, RoundingMode.HALF_UP).toPlainString(); }
    /** 分析数值最多保留四位小数并移除尾零。 */
    private BigDecimal number(BigDecimal value) { return value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros(); }
    /** 从弱类型值读取非空文本。 */
    private String text(Object value, String fallback) { return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value); }
    /** 从字符串读取非空文本。 */
    private String blank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }

    /** 行业归因聚合器，累加贡献率、盈亏、市值和证券数量。 */
    private static final class IndustryBucket {
        private BigDecimal contribution = BigDecimal.ZERO;
        private BigDecimal pnl = BigDecimal.ZERO;
        private BigDecimal marketValue = BigDecimal.ZERO;
        private int count;

        /** 合并一条证券归因数据。 */
        private void add(BigDecimal contributionValue, BigDecimal pnlValue, BigDecimal marketValueValue) {
            contribution = contribution.add(contributionValue);
            pnl = pnl.add(pnlValue);
            marketValue = marketValue.add(marketValueValue);
            count++;
        }
    }
}
