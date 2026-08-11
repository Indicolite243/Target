package com.stockmanager.analysis.service;

import com.stockmanager.account.document.AccountSnapshot;
import com.stockmanager.account.repository.AccountSnapshotRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads immutable MongoDB account snapshots for historical analysis. */
@Service
public class AccountSnapshotHistoryService {
    private final AccountSnapshotRepository repository;

    public AccountSnapshotHistoryService(AccountSnapshotRepository repository) {
        this.repository = repository;
    }

    public Map<String, Object> portfolioHistory(Long accountId, LocalDate start, LocalDate end) {
        return portfolioHistory(accountId, start, end, "DAILY");
    }

    public Map<String, Object> portfolioHistory(Long accountId, LocalDate start, LocalDate end,
                                                String granularity) {
        List<AccountSnapshot> snapshots = snapshots(accountId, start, end);
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
            Map<LocalDate, AccountSnapshot> daily = new LinkedHashMap<>();
            for (AccountSnapshot snapshot : snapshots) {
                daily.put(snapshot.getSnapshotTime().toLocalDate(), snapshot);
            }
            values = daily.entrySet().stream().map(entry -> {
                Map<String, String> point = new LinkedHashMap<>();
                point.put("date", entry.getKey().toString());
                point.put("value", money(entry.getValue().getTotalAsset()));
                return point;
            }).toList();
        }

        AccountSnapshot startSnapshot = snapshots.getFirst();
        AccountSnapshot endSnapshot = snapshots.getLast();
        if (!startSnapshot.getSnapshotTime().toLocalDate().equals(start)) {
            warnings.add("区间起点没有精确快照，已使用区间内最早一条快照");
        }
        if (!endSnapshot.getSnapshotTime().toLocalDate().equals(end)) {
            warnings.add("区间终点没有精确快照，已使用区间内最新一条快照");
        }
        warnings.add("曲线来自 MongoDB 中的 QMT 账户快照，反映实际采集时的持仓变化");
        warnings.add("ALL".equalsIgnoreCase(granularity)
                ? "当前粒度：全部快照"
                : "当前粒度：每日最后一条快照");

        Map<String, Object> result = result(values,
                positionHistory(startSnapshot, endSnapshot), warnings, start, end);
        result.put("rangeStart", values.getFirst().get("date"));
        result.put("rangeEnd", values.getLast().get("date"));
        result.put("tradingDays", values.size());
        return result;
    }

    private List<AccountSnapshot> snapshots(Long accountId, LocalDate start, LocalDate end) {
        LocalDateTime from = start.atStartOfDay();
        LocalDateTime to = end.plusDays(1).atStartOfDay().minusNanos(1);
        return repository.findByAccountIdAndSnapshotTimeBetweenOrderBySnapshotTimeAsc(accountId, from, to);
    }

    private List<Map<String, Object>> positionHistory(AccountSnapshot start, AccountSnapshot end) {
        Map<String, Map<String, String>> before = bySymbol(start.getPositions());
        Map<String, Map<String, String>> after = bySymbol(end.getPositions());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : after.entrySet()) {
            String symbol = entry.getKey();
            Map<String, String> current = entry.getValue();
            Map<String, String> previous = before.getOrDefault(symbol, Map.of());
            BigDecimal endValue = decimal(current.get("marketValue"));
            BigDecimal startValue = decimal(previous.get("marketValue"));
            BigDecimal endPrice = decimal(current.get("lastPrice"));
            BigDecimal startPrice = decimal(previous.get("lastPrice"));
            if (startPrice.signum() == 0) startPrice = decimal(current.get("costPrice"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("symbol", symbol);
            row.put("securityName", text(current.get("securityName"), symbol));
            row.put("quantity", text(current.get("quantity"), "0"));
            row.put("costPrice", moneyDecimal(decimal(current.get("costPrice")), 4));
            row.put("startPrice", moneyDecimal(startPrice, 4));
            row.put("endPrice", moneyDecimal(endPrice, 4));
            row.put("marketValue", money(endValue));
            row.put("profitLoss", text(current.get("profitLoss"), "0.00"));
            row.put("periodPnl", money(endValue.subtract(startValue)));
            row.put("industry", text(current.get("industry"), "其他"));
            row.put("region", text(current.get("region"), "其他市场"));
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Map<String, String>> bySymbol(List<Map<String, String>> positions) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        if (positions != null) {
            for (Map<String, String> position : positions) {
                String symbol = text(position.get("symbol"), "");
                if (!symbol.isBlank()) result.put(symbol, position);
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
        result.put("source", "mongodb_account_snapshots");
        result.put("calculationMethod", "QMT账户快照总资产与快照持仓变化");
        result.put("rangeStart", start.toString());
        result.put("rangeEnd", end.toString());
        result.put("tradingDays", values.size());
        return result;
    }

    private BigDecimal decimal(String value) {
        try { return value == null ? BigDecimal.ZERO : new BigDecimal(value); }
        catch (Exception ignored) { return BigDecimal.ZERO; }
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
}
