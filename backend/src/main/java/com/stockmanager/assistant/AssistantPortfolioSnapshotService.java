package com.stockmanager.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmanager.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 生成只读、可追溯的当前持仓快照；不触发 QMT 同步，也不包含资金账号。 */
@Service
public class AssistantPortfolioSnapshotService {
    private static final int MAX_MODEL_JSON_CHARS = 30_000;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AssistantPortfolioSnapshotService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record FrozenSnapshot(String id, String modelJson) {}

    @Transactional
    public FrozenSnapshot getOrCreate(long userId, String conversationId) {
        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT s.id,s.snapshot_json FROM ai_conversation c
                JOIN ai_data_snapshot s ON s.id=c.active_snapshot_id
                WHERE c.id=? AND c.user_id=?
                """, conversationId, userId);
        if (!existing.isEmpty()) {
            String json = String.valueOf(existing.getFirst().get("snapshot_json"));
            return new FrozenSnapshot(String.valueOf(existing.getFirst().get("id")), compactForModel(json));
        }

        List<AccountRow> accounts = jdbc.query("""
                SELECT id,broker,environment,currency,total_asset,cash,market_value,
                       profit_loss,last_sync_time,data_version
                FROM account WHERE user_id=? AND status='ACTIVE' ORDER BY id
                """, (rs, n) -> new AccountRow(rs.getLong("id"), rs.getString("broker"),
                rs.getString("environment"), rs.getString("currency"),
                rs.getBigDecimal("total_asset"), rs.getBigDecimal("cash"), rs.getBigDecimal("market_value"),
                rs.getBigDecimal("profit_loss"), rs.getObject("last_sync_time", LocalDateTime.class),
                rs.getObject("data_version") == null
                        ? null : rs.getLong("data_version")), userId);

        LocalDateTime capturedAt = LocalDateTime.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("snapshotFrozenAt", capturedAt.toString());
        payload.put("source", "MYSQL_LATEST_CONFIRMED_SNAPSHOT");
        Long accountId = null;
        String sourceVersion = null;
        if (accounts.size() != 1) {
            payload.put("available", false);
            payload.put("reason", accounts.isEmpty() ? "当前登录用户没有可用账户" : "当前登录用户存在多个可用账户，第一版无法自动选择");
            payload.put("activeAccountCount", accounts.size());
        } else {
            AccountRow account = accounts.getFirst();
            accountId = account.id();
            sourceVersion = account.dataVersion() == null ? null : String.valueOf(account.dataVersion());
            List<PositionRow> positions = positions(account.id());
            payload.put("available", true);
            payload.put("dataVersion", sourceVersion);
            payload.put("sourceDataAsOf", account.lastSyncTime() == null ? null : account.lastSyncTime().toString());
            payload.put("account", accountPayload(account));
            payload.put("diagnostics", diagnostics(account, positions));
            payload.put("positions", positions.stream().map(this::positionPayload).toList());
            payload.put("limitations", List.of(
                    "这是 MySQL 中最近一次已确认快照，不代表实时行情；数据截至时间取 sourceDataAsOf，不是 snapshotFrozenAt",
                    "当前持仓快照本身不包含历史成交、回测结果或行业穿透；相关问题需要调用对应工具",
                    "账户号码、用户身份和系统内部主键未发送给模型"));
        }

        String snapshotJson = json(payload);
        String snapshotId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ai_data_snapshot(id,conversation_id,account_id,snapshot_type,snapshot_json,captured_at,source_version) VALUES(?,?,?,?,?,?,?)",
                snapshotId, conversationId, accountId, "PORTFOLIO", snapshotJson, Timestamp.valueOf(capturedAt), sourceVersion);
        jdbc.update("UPDATE ai_conversation SET account_id=?,active_snapshot_id=?,updated_at=? WHERE id=? AND user_id=?",
                accountId, snapshotId, Timestamp.valueOf(capturedAt), conversationId, userId);
        return new FrozenSnapshot(snapshotId, compactForModel(snapshotJson));
    }

    private List<PositionRow> positions(long accountId) {
        return jdbc.query("""
                SELECT security_code,security_name,quantity,available_quantity,cost_price,last_price,
                       market_value,profit_loss,industry,region
                FROM position WHERE account_id=? ORDER BY market_value DESC,security_code LIMIT 300
                """, (rs, n) -> new PositionRow(rs.getString("security_code"), rs.getString("security_name"),
                rs.getBigDecimal("quantity"), rs.getBigDecimal("available_quantity"), rs.getBigDecimal("cost_price"),
                rs.getBigDecimal("last_price"), rs.getBigDecimal("market_value"), rs.getBigDecimal("profit_loss"),
                rs.getString("industry"), rs.getString("region")), accountId);
    }

    private Map<String, Object> accountPayload(AccountRow row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("broker", row.broker());
        result.put("environment", row.environment()); result.put("currency", row.currency());
        result.put("totalAsset", value(row.totalAsset())); result.put("cash", value(row.cash()));
        result.put("marketValue", value(row.marketValue())); result.put("profitLoss", value(row.profitLoss()));
        return result;
    }

    private Map<String, Object> positionPayload(PositionRow row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", row.code()); result.put("name", row.name());
        result.put("quantity", value(row.quantity())); result.put("availableQuantity", value(row.availableQuantity()));
        result.put("costPrice", value(row.costPrice())); result.put("lastPrice", value(row.lastPrice()));
        result.put("marketValue", value(row.marketValue())); result.put("profitLoss", value(row.profitLoss()));
        result.put("industry", row.industry()); result.put("region", row.region());
        return result;
    }

    private Map<String, Object> diagnostics(AccountRow account, List<PositionRow> positions) {
        BigDecimal marketValue = positions.stream().map(row -> value(row.marketValue()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<BigDecimal> weights = positions.stream().map(row -> ratio(value(row.marketValue()), marketValue)).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("positionCount", positions.size());
        result.put("cashRatio", ratio(value(account.cash()), value(account.totalAsset())));
        result.put("top1Weight", sum(weights, 1)); result.put("top3Weight", sum(weights, 3));
        result.put("top5Weight", sum(weights, 5));
        result.put("profitablePositions", positions.stream().filter(row -> value(row.profitLoss()).signum() > 0).count());
        result.put("lossPositions", positions.stream().filter(row -> value(row.profitLoss()).signum() < 0).count());
        result.put("unknownIndustryPositions", positions.stream().filter(row -> row.industry() == null || row.industry().isBlank()).count());
        result.put("weightDefinition", "持仓市值/全部持仓市值，现金不进入个股权重分母");
        return result;
    }

    private BigDecimal sum(List<BigDecimal> values, int limit) {
        return values.stream().limit(limit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    private BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        return denominator.signum() == 0 ? BigDecimal.ZERO
                : numerator.divide(denominator, 6, RoundingMode.HALF_UP);
    }
    private BigDecimal value(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    private String compactForModel(String snapshotJson) {
        if (snapshotJson.length() <= MAX_MODEL_JSON_CHARS) return snapshotJson;
        try {
            Map<String, Object> snapshot = mapper.readValue(snapshotJson, new TypeReference<>() {});
            Object rows = snapshot.get("positions");
            if (rows instanceof List<?> list && list.size() > 100) {
                snapshot.put("positions", new ArrayList<>(list.subList(0, 100)));
                snapshot.put("positionsTruncatedForModel", true);
            }
            String compact = mapper.writeValueAsString(snapshot);
            if (compact.length() <= MAX_MODEL_JSON_CHARS) return compact;
            snapshot.remove("positions");
            snapshot.put("positionsOmittedForModel", true);
            return mapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(500102, "账户快照读取失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private String json(Map<String, Object> value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) {
            throw new BusinessException(500101, "账户快照生成失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private record AccountRow(long id, String broker, String environment, String currency,
                              BigDecimal totalAsset, BigDecimal cash, BigDecimal marketValue, BigDecimal profitLoss,
                              LocalDateTime lastSyncTime, Long dataVersion) {}
    private record PositionRow(String code, String name, BigDecimal quantity, BigDecimal availableQuantity,
                               BigDecimal costPrice, BigDecimal lastPrice, BigDecimal marketValue,
                               BigDecimal profitLoss, String industry, String region) {}
}
