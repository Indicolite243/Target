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

/** Reads immutable MySQL account and position snapshots for historical analysis. */
@Service
public class AccountSnapshotHistoryService {
    private final AccountHistorySnapshotMapper accountSnapshotMapper;
    private final PositionHistorySnapshotMapper positionSnapshotMapper;

    public AccountSnapshotHistoryService(AccountHistorySnapshotMapper accountSnapshotMapper,
                                         PositionHistorySnapshotMapper positionSnapshotMapper) {
        this.accountSnapshotMapper = accountSnapshotMapper;
        this.positionSnapshotMapper = positionSnapshotMapper;
    }

    public Map<String, Object> portfolioHistory(Long accountId, LocalDate start, LocalDate end) {
        return portfolioHistory(accountId, start, end, "DAILY");
    }

    public Map<String, Object> portfolioHistory(Long accountId, LocalDate start, LocalDate end,
                                                String granularity) {
        List<AccountHistorySnapshot> snapshots = snapshots(accountId, start, end);
        List<String> warnings = new ArrayList<>();
        if (snapshots.isEmpty()) {
            warnings.add("所选区间没有账户快照，请先启动服务并等待 QMT 定时采集或手动同步账户");
            return result(List.of(), List.of(), warnings, start, end);
        }

        List<Map<String, String>> values;
        if ("ALL".equalsIgnoreCase(granularity)) {
            values = snapshots.stream().map(snapshot -> {
                Map<String, String> point = new LinkedHashMap<>();
                point.put("date", snapshot.getSnapshotTime().toString());
                point.put("value", money(snapshot.getTotalAsset()));
                return point;
            }).toList();
        } else {
            // DAILY keeps the last captured state for each trading/calendar day.
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

        Map<String, Object> result = result(values,
                positionHistory(accountId, startSnapshot, endSnapshot, warnings), warnings, start, end);
        result.put("rangeStart", values.getFirst().get("date"));
        result.put("rangeEnd", values.getLast().get("date"));
        result.put("tradingDays", values.size());
        return result;
    }

    private List<AccountHistorySnapshot> snapshots(Long accountId, LocalDate start, LocalDate end) {
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
        AccountHistorySnapshot startPositions = positionSnapshotFor(accountId, start.getSnapshotTime(), "起点", warnings);
        AccountHistorySnapshot endPositions = positionSnapshotFor(accountId, end.getSnapshotTime(), "终点", warnings);
        Map<String, PositionHistorySnapshot> before = bySymbol(positions(startPositions));
        Map<String, PositionHistorySnapshot> after = bySymbol(positions(endPositions));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, PositionHistorySnapshot> entry : after.entrySet()) {
            String symbol = entry.getKey();
            PositionHistorySnapshot current = entry.getValue();
            PositionHistorySnapshot previous = before.get(symbol);
            BigDecimal endValue = zero(current.getMarketValue());
            BigDecimal startValue = previous == null ? BigDecimal.ZERO : zero(previous.getMarketValue());
            BigDecimal endPrice = zero(current.getLastPrice());
            BigDecimal startPrice = previous == null ? BigDecimal.ZERO : zero(previous.getLastPrice());
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
        if (snapshot == null) return List.of();
        return positionSnapshotMapper.selectList(Wrappers.<PositionHistorySnapshot>lambdaQuery()
                .eq(PositionHistorySnapshot::getSnapshotId, snapshot.getId())
                .orderByAsc(PositionHistorySnapshot::getSecurityCode));
    }

    private Map<String, PositionHistorySnapshot> bySymbol(List<PositionHistorySnapshot> positions) {
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
