package com.stockmanager.analysis.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stockmanager.account.entity.AccountHistorySnapshot;
import com.stockmanager.account.entity.PositionHistorySnapshot;
import com.stockmanager.account.mapper.AccountHistorySnapshotMapper;
import com.stockmanager.account.mapper.PositionHistorySnapshotMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读取MySQL不可变账户/持仓快照，并组装时间对比、风险和归因共用的历史结构。
 * 本服务只读数据库，不调用QMT，也不会把“当前持仓穿越回放”伪装成真实账户历史。
 */
@Service
public class AccountSnapshotHistoryService {
    private final AccountHistorySnapshotMapper accountSnapshotMapper;
    private final PositionHistorySnapshotMapper positionSnapshotMapper;

    /** 注入账户历史和持仓历史 Mapper。 */
    public AccountSnapshotHistoryService(AccountHistorySnapshotMapper accountSnapshotMapper,
                                         PositionHistorySnapshotMapper positionSnapshotMapper) {
        this.accountSnapshotMapper = accountSnapshotMapper;
        this.positionSnapshotMapper = positionSnapshotMapper;
    }

    /** 使用默认 DAILY 粒度读取统一组合历史结构。 */
    public Map<String, Object> portfolioHistory(Long accountId, LocalDate start, LocalDate end) {
        return portfolioHistory(accountId, start, end, "DAILY");
    }

    /**
     * 按粒度恢复账户资产时间序列和对应持仓历史，供时间对比、风险和归因复用。
     */
    public Map<String, Object> portfolioHistory(Long accountId, LocalDate start, LocalDate end,
                                                String granularity) {
        // 先按时间升序读取区间内全部账户资产快照，后续粒度转换依赖该稳定顺序。
        List<AccountHistorySnapshot> snapshots = snapshots(accountId, start, end);
        // warnings随结果返回前端，用来解释缺失边界和持仓快照替代情况。
        List<String> warnings = new ArrayList<>();
        if (snapshots.isEmpty()) {
            warnings.add("所选区间没有账户快照，请先启动服务并等待 QMT 定时采集或手动同步账户");
            return result(List.of(), List.of(), warnings, start, end);
        }

        List<Map<String, String>> values;
        if ("ALL".equalsIgnoreCase(granularity)) {
            // ALL保留每一次盘中/收盘采样，date包含具体时间，适合查看日内变化。
            values = snapshots.stream().map(snapshot -> {
                Map<String, String> point = new LinkedHashMap<>();
                point.put("date", snapshot.getSnapshotTime().toString());
                point.put("value", money(snapshot.getTotalAsset()));
                return point;
            }).toList();
        } else {
            // DAILY按日期覆盖：由于snapshots升序，最终保留每个自然日最后一次采集状态。
            Map<LocalDate, AccountHistorySnapshot> daily = new LinkedHashMap<>();
            for (AccountHistorySnapshot snapshot : snapshots) {
                daily.put(snapshot.getSnapshotTime().toLocalDate(), snapshot);
            }
            values = daily.entrySet().stream().map(entry -> {
                Map<String, String> point = new LinkedHashMap<>();
                point.put("date", entry.getKey().toString());
                point.put("value", money(entry.getValue().getTotalAsset()));
                return point;
            }).toList();
        }

        // 起止边界使用区间内实际第一/最后快照，而不是伪造用户选择日期的资产值。
        AccountHistorySnapshot startSnapshot = snapshots.getFirst();
        AccountHistorySnapshot endSnapshot = snapshots.getLast();
        if (!startSnapshot.getSnapshotTime().toLocalDate().equals(start)) {
            warnings.add("区间起点没有精确快照，已使用区间内最早一条快照");
        }
        if (!endSnapshot.getSnapshotTime().toLocalDate().equals(end)) {
            warnings.add("区间终点没有精确快照，已使用区间内最新一条快照");
        }
        warnings.add("曲线来自 MySQL 中的不可变账户快照，反映实际采集时的持仓变化");
        warnings.add("ALL".equalsIgnoreCase(granularity)
                ? "当前粒度：全部快照"
                : "当前粒度：每日最后一条快照");

        // 持仓期间变化只需要起点与终点两份完整持仓，不为每个资产点展开全量持仓。
        Map<String, Object> result = result(values,
                positionHistory(accountId, startSnapshot, endSnapshot, warnings), warnings, start, end);
        result.put("rangeStart", values.getFirst().get("date"));
        result.put("rangeEnd", values.getLast().get("date"));
        result.put("tradingDays", values.size());
        return result;
    }

    private List<AccountHistorySnapshot> snapshots(Long accountId, LocalDate start, LocalDate end) {
        // 将LocalDate扩展为闭区间[start 00:00:00, end 23:59:59.999...]。
        LocalDateTime from = start.atStartOfDay();
        LocalDateTime to = end.plusDays(1).atStartOfDay().minusNanos(1);
        return accountSnapshotMapper.selectList(Wrappers.<AccountHistorySnapshot>lambdaQuery()
                .eq(AccountHistorySnapshot::getAccountId, accountId)
                .between(AccountHistorySnapshot::getSnapshotTime, from, to)
                .orderByAsc(AccountHistorySnapshot::getSnapshotTime)
                .orderByAsc(AccountHistorySnapshot::getId));
    }

    private List<Map<String, Object>> positionHistory(Long accountId, AccountHistorySnapshot start,
                                                      AccountHistorySnapshot end, List<String> warnings) {
        // 资产快照不一定每次都带持仓，因此分别查找边界附近最近的“完整持仓快照”。
        AccountHistorySnapshot startPositions = positionSnapshotFor(accountId, start.getSnapshotTime(), "起点", warnings);
        AccountHistorySnapshot endPositions = positionSnapshotFor(accountId, end.getSnapshotTime(), "终点", warnings);
        // 转成证券代码索引后可以O(1)匹配同一证券的起止记录。
        Map<String, PositionHistorySnapshot> before = bySymbol(positions(startPositions));
        Map<String, PositionHistorySnapshot> after = bySymbol(positions(endPositions));
        List<Map<String, Object>> rows = new ArrayList<>();
        // 以终点仍持有的证券为结果主体；区间内已经完全卖出的证券当前不会出现在此列表。
        for (Map.Entry<String, PositionHistorySnapshot> entry : after.entrySet()) {
            String symbol = entry.getKey();
            PositionHistorySnapshot current = entry.getValue();
            PositionHistorySnapshot previous = before.get(symbol);
            // periodPnl当前采用“终点市值-起点市值”展示组合贡献，包含数量变化影响。
            BigDecimal endValue = zero(current.getMarketValue());
            BigDecimal startValue = previous == null ? BigDecimal.ZERO : zero(previous.getMarketValue());
            BigDecimal endPrice = zero(current.getLastPrice());
            BigDecimal startPrice = previous == null ? BigDecimal.ZERO : zero(previous.getLastPrice());
            // 起点没有该证券或价格为空时，用终点记录中的成本价作为可解释回退值。
            if (startPrice.signum() == 0) startPrice = zero(current.getCostPrice());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("symbol", symbol);
            row.put("securityName", text(current.getSecurityName(), symbol));
            row.put("quantity", number(current.getQuantity()));
            row.put("costPrice", moneyDecimal(zero(current.getCostPrice()), 4));
            row.put("startPrice", moneyDecimal(startPrice, 4));
            row.put("endPrice", moneyDecimal(endPrice, 4));
            row.put("marketValue", money(endValue));
            row.put("profitLoss", money(zero(current.getProfitLoss())));
            row.put("periodPnl", money(endValue.subtract(startValue)));
            row.put("industry", text(current.getIndustry(), "其他"));
            row.put("region", text(current.getRegion(), "其他市场"));
            rows.add(row);
        }
        return rows;
    }

    private AccountHistorySnapshot positionSnapshotFor(Long accountId, LocalDateTime at,
                                                        String boundary, List<String> warnings) {
        // 优先使用边界时刻之前最近的完整快照，避免引入“未来持仓”；没有时才向后寻找第一份完整快照。
        AccountHistorySnapshot snapshot = accountSnapshotMapper.findLatestWithPositionsAtOrBefore(accountId, at);
        if (snapshot == null) snapshot = accountSnapshotMapper.findFirstWithPositionsAtOrAfter(accountId, at);
        if (snapshot == null) {
            warnings.add("区间" + boundary + "没有完整持仓快照，持仓归因结果为空");
        } else if (!snapshot.getSnapshotTime().equals(at)) {
            warnings.add("区间" + boundary + "持仓使用最近的完整快照：" + snapshot.getSnapshotTime());
        }
        return snapshot;
    }

    private List<PositionHistorySnapshot> positions(AccountHistorySnapshot snapshot) {
        // 没有可用边界快照时返回空集合，让调用方产生空归因和明确warning。
        if (snapshot == null) return List.of();
        return positionSnapshotMapper.selectList(Wrappers.<PositionHistorySnapshot>lambdaQuery()
                .eq(PositionHistorySnapshot::getSnapshotId, snapshot.getId())
                .orderByAsc(PositionHistorySnapshot::getSecurityCode));
    }

    private Map<String, PositionHistorySnapshot> bySymbol(List<PositionHistorySnapshot> positions) {
        // LinkedHashMap保留数据库证券代码顺序，使接口结果和测试输出稳定。
        Map<String, PositionHistorySnapshot> result = new LinkedHashMap<>();
        for (PositionHistorySnapshot position : positions) {
            String symbol = text(position.getSecurityCode(), "");
            if (!symbol.isBlank()) {
                result.put(symbol, position);
            }
        }
        return result;
    }

    private Map<String, Object> result(List<?> values, List<?> positionHistory,
                                       List<String> warnings, LocalDate start, LocalDate end) {
        // 三个消费者统一使用portfolioValues/positionHistory字段，避免各业务重复定义历史协议。
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("portfolioValues", values);
        result.put("positionHistory", positionHistory);
        result.put("warnings", warnings);
        result.put("source", "mysql_account_snapshots");
        result.put("calculationMethod", "QMT账户快照总资产与快照持仓变化");
        result.put("rangeStart", start.toString());
        result.put("rangeEnd", end.toString());
        result.put("tradingDays", values.size());
        return result;
    }

    private String money(BigDecimal value) {
        // 金额作为字符串返回，防止JSON/JavaScript二进制浮点损失金融小数。
        return value == null ? "0.00" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String moneyDecimal(BigDecimal value, int scale) {
        return value == null ? "0" : value.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    private String number(BigDecimal value) {
        return zero(value).stripTrailingZeros().toPlainString();
    }
}
