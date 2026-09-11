package com.stockmanager.assistant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmanager.analysis.service.AnalysisService;
import com.stockmanager.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 为模型提供当前用户最近30天的只读个股与行业收益贡献，并在会话内冻结结果。 */
@Service
public class AssistantAttributionToolService {
    private static final int MAX_MODEL_JSON_CHARS = 30_000;
    private static final int MAX_STOCK_ROWS = 100;
    private static final int MAX_INDUSTRY_ROWS = 50;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AnalysisService analysisService;

    public AssistantAttributionToolService(JdbcTemplate jdbc, ObjectMapper mapper,
                                           AnalysisService analysisService) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.analysisService = analysisService;
    }

    public record FrozenAttribution(String id, String modelJson) {}

    @Transactional
    public FrozenAttribution getOrCreate(long userId, String conversationId, String traceId) {
        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT s.id,s.snapshot_json FROM ai_conversation c
                JOIN ai_data_snapshot s ON s.id=c.active_attribution_snapshot_id
                WHERE c.id=? AND c.user_id=? AND s.snapshot_type='ATTRIBUTION'
                """, conversationId, userId);
        if (!existing.isEmpty()) {
            String json = String.valueOf(existing.getFirst().get("snapshot_json"));
            return new FrozenAttribution(String.valueOf(existing.getFirst().get("id")), compactForModel(json));
        }

        List<Map<String, Object>> accounts = jdbc.queryForList("""
                SELECT id,data_version FROM account
                WHERE user_id=? AND status='ACTIVE' ORDER BY id
                """, userId);
        LocalDateTime capturedAt = LocalDateTime.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("snapshotFrozenAt", capturedAt.toString());
        payload.put("snapshotType", "PERFORMANCE_ATTRIBUTION");
        payload.put("selectionRule", "当前登录用户唯一账户最近30天的MySQL历史快照，同一会话首次读取后固定");

        Long accountId = null;
        String sourceVersion = null;
        if (accounts.size() != 1) {
            payload.put("available", false);
            payload.put("reason", accounts.isEmpty()
                    ? "当前登录用户没有可用账户"
                    : "当前登录用户存在多个可用账户，第一版无法自动选择");
            payload.put("activeAccountCount", accounts.size());
        } else {
            Map<String, Object> account = accounts.getFirst();
            accountId = ((Number) account.get("id")).longValue();
            Object version = account.get("data_version");
            sourceVersion = version == null ? null : String.valueOf(version);
            Map<String, Object> attribution = analysisService.attribution(
                    userId, accountId, "INDUSTRY", "mysql", null, null, traceId);
            List<?> stockRows = list(attribution.get("attributionRows"));
            payload.put("available", !stockRows.isEmpty());
            if (stockRows.isEmpty()) payload.put("reason", "最近30天没有可用于归因的完整持仓快照");
            payload.put("dataSource", attribution.getOrDefault("data_source", "mysql_history"));
            payload.put("rangeStart", attribution.get("range_start"));
            payload.put("rangeEnd", attribution.get("range_end"));
            payload.put("sampleCount", attribution.getOrDefault("sample_count", 0));
            payload.put("summary", attribution.getOrDefault("summary", Map.of()));
            payload.put("stockContributions", stockRows);
            payload.put("industryContributions", list(attribution.get("industryRows")));
            payload.put("calculationMethod", attribution.getOrDefault("calculation_method", ""));
            payload.put("warnings", list(attribution.get("warnings")));
            payload.put("limitations", List.of(
                    "这是基于历史持仓快照的个股与行业收益贡献，不是完整Brinson配置/选股/交互归因",
                    "个股贡献按终点市值减起点市值计算，包含加减仓影响；returnRate只表示起止价格涨跌，两者方向可能不同",
                    "历史行业缺失时会用当前持仓行业或证券名称规则做展示级补齐，不代表权威行业主数据",
                    "当前未包含风格因子暴露和逐笔成交贡献",
                    "结果不构成投资建议；账户号码、用户身份和系统内部主键未发送给模型"));
        }

        String snapshotJson = json(payload);
        String snapshotId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO ai_data_snapshot
                    (id,conversation_id,account_id,snapshot_type,snapshot_json,captured_at,source_version)
                VALUES(?,?,?,?,?,?,?)
                """, snapshotId, conversationId, accountId, "ATTRIBUTION", snapshotJson,
                Timestamp.valueOf(capturedAt), sourceVersion);
        jdbc.update("""
                UPDATE ai_conversation
                SET account_id=COALESCE(account_id,?),active_attribution_snapshot_id=?,updated_at=?
                WHERE id=? AND user_id=?
                """, accountId, snapshotId, Timestamp.valueOf(capturedAt), conversationId, userId);
        return new FrozenAttribution(snapshotId, compactForModel(snapshotJson));
    }

    private String compactForModel(String rawJson) {
        if (rawJson.length() <= MAX_MODEL_JSON_CHARS) return rawJson;
        try {
            Map<String, Object> value = mapper.readValue(rawJson, new TypeReference<>() {});
            trim(value, "stockContributions", MAX_STOCK_ROWS);
            trim(value, "industryContributions", MAX_INDUSTRY_ROWS);
            String compact = mapper.writeValueAsString(value);
            if (compact.length() <= MAX_MODEL_JSON_CHARS) return compact;
            value.remove("stockContributions");
            value.put("stockContributionsOmittedForModel", true);
            compact = mapper.writeValueAsString(value);
            if (compact.length() <= MAX_MODEL_JSON_CHARS) return compact;
            value.remove("industryContributions");
            value.put("industryContributionsOmittedForModel", true);
            compact = mapper.writeValueAsString(value);
            if (compact.length() > MAX_MODEL_JSON_CHARS)
                throw new BusinessException(413104, "归因上下文超过模型上限", HttpStatus.PAYLOAD_TOO_LARGE);
            return compact;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(500104, "归因快照读取失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void trim(Map<String, Object> value, String key, int max) {
        Object rows = value.get(key);
        if (rows instanceof List<?> list && list.size() > max) {
            value.put(key, new ArrayList<>(list.subList(0, max)));
            value.put(key + "TruncatedForModel", true);
        }
    }

    private List<?> list(Object value) { return value instanceof List<?> list ? list : List.of(); }

    private String json(Map<String, Object> value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) {
            throw new BusinessException(500104, "归因快照生成失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
