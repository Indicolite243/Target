package com.stockmanager.assistant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmanager.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 为模型选择当前用户最近创建的回测，并输出去路径、去内部标识的只读分析上下文。 */
@Service
public class AssistantBacktestToolService {
    private static final int MAX_MODEL_JSON_CHARS = 30_000;
    private static final int MAX_CURVE_POINTS = 240;
    private static final int MAX_SOURCE_CHARS = 16_000;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AssistantBacktestToolService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record SelectedBacktest(Long taskId, String modelJson) {}

    @Transactional
    public SelectedBacktest getOrSelect(long userId, String conversationId, boolean includeSource) {
        List<Long> selected = jdbc.query("""
                SELECT active_backtest_task_id FROM ai_conversation
                WHERE id=? AND user_id=? AND active_backtest_task_id IS NOT NULL
                """, (rs, n) -> rs.getLong("active_backtest_task_id"), conversationId, userId);
        Long taskId = selected.isEmpty() ? null : selected.getFirst();
        TaskRow task = taskId == null ? latest(userId) : owned(userId, taskId);
        if (task == null) {
            return new SelectedBacktest(null, json(Map.of(
                    "available", false,
                    "reason", "当前登录用户还没有回测任务",
                    "selectionRule", "当前用户最近创建的回测任务")));
        }
        if (taskId == null) {
            jdbc.update("UPDATE ai_conversation SET active_backtest_task_id=? WHERE id=? AND user_id=?",
                    task.id(), conversationId, userId);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("available", "SUCCEEDED".equals(task.status()));
        output.put("status", task.status());
        output.put("progress", task.progress());
        output.put("stage", task.stage());
        output.put("createdAt", text(task.createdAt()));
        output.put("finishedAt", text(task.finishedAt()));
        output.put("selectionRule", "本会话首次使用时固定的、当前用户最近创建的回测任务");
        if (!"SUCCEEDED".equals(task.status())) {
            output.put("reason", switch (task.status()) {
                case "FAILED" -> "最近一次回测执行失败，不回退到更早的成功结果";
                case "CANCELLED" -> "最近一次回测已取消，不回退到更早的成功结果";
                default -> "最近一次回测尚未完成，不能读取半成品";
            });
            return new SelectedBacktest(task.id(), json(output));
        }

        RunRow run = run(userId, task.id());
        if (run == null) {
            output.put("available", false);
            output.put("reason", "任务已成功但持久化回测结果不存在");
            return new SelectedBacktest(task.id(), json(output));
        }
        output.put("strategyFilename", run.strategyFilename());
        output.put("engineType", run.engineType());
        output.put("benchmarkSymbol", run.benchmarkSymbol());
        output.put("startDate", text(run.startDate()));
        output.put("endDate", text(run.endDate()));
        output.putAll(resultPayload(run.resultJson()));
        output.put("limitations", List.of(
                "回测结果不等同于实际账户收益，也不是未来收益保证",
                "曲线超过240点时按全区间等距抽样，首尾点始终保留",
                "工具不会运行回测、修改源码或写入策略文件"));
        if (includeSource) addSource(output, run.strategySource());
        return new SelectedBacktest(task.id(), boundedJson(output, includeSource));
    }

    private TaskRow latest(long userId) {
        List<TaskRow> rows = jdbc.query("""
                SELECT id,status,progress,stage,created_at,finished_at FROM quant_task
                WHERE user_id=? AND task_type='BACKTEST' ORDER BY created_at DESC,id DESC LIMIT 1
                """, this::taskRow, userId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private TaskRow owned(long userId, long taskId) {
        List<TaskRow> rows = jdbc.query("""
                SELECT id,status,progress,stage,created_at,finished_at FROM quant_task
                WHERE id=? AND user_id=? AND task_type='BACKTEST'
                """, this::taskRow, taskId, userId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private TaskRow taskRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new TaskRow(rs.getLong("id"), rs.getString("status"), rs.getInt("progress"),
                rs.getString("stage"), rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("finished_at", LocalDateTime.class));
    }

    private RunRow run(long userId, long taskId) {
        List<RunRow> rows = jdbc.query("""
                SELECT strategy_filename,engine_type,benchmark_symbol,start_date,end_date,result_json,strategy_source
                FROM backtest_run WHERE task_id=? AND user_id=?
                """, (rs, n) -> new RunRow(rs.getString("strategy_filename"), rs.getString("engine_type"),
                rs.getString("benchmark_symbol"), rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class), rs.getString("result_json"),
                rs.getString("strategy_source")), taskId, userId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Map<String, Object> resultPayload(String rawJson) {
        try {
            Map<String, Object> root = mapper.readValue(rawJson, new TypeReference<>() {});
            Map<String, Object> data = map(root.get("data"));
            if (data.isEmpty()) data = root;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("metrics", map(data.get("metrics")));
            result.put("trajectory", trajectory(data, MAX_CURVE_POINTS));
            result.put("execution", execution(map(data.get("execution_meta")), map(data.get("engine"))));
            return result;
        } catch (Exception exception) {
            throw new BusinessException(500103, "回测结果数据损坏", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private Map<String, Object> trajectory(Map<String, Object> data, int maxPoints) {
        List<?> dates = list(data.get("dates"));
        List<?> strategy = list(data.get("strategy"));
        List<?> benchmark = list(data.get("benchmark"));
        List<?> excess = list(data.get("excess"));
        int size = Math.min(Math.min(dates.size(), strategy.size()), Math.min(benchmark.size(), excess.size()));
        List<Integer> indexes = indexes(size, maxPoints);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("originalPointCount", size);
        result.put("sampledPointCount", indexes.size());
        result.put("downsampled", size > indexes.size());
        result.put("dates", pick(dates, indexes));
        result.put("strategyReturnPct", pick(strategy, indexes));
        result.put("benchmarkReturnPct", pick(benchmark, indexes));
        result.put("excessReturnPct", pick(excess, indexes));
        return result;
    }

    private Map<String, Object> execution(Map<String, Object> meta, Map<String, Object> engine) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("requested_engine", "resolved_engine", "executor_type", "strategy_format",
                "benchmark_symbol_requested", "benchmark_symbol", "benchmark_data_source", "benchmark_warning",
                "bear_protection_enabled", "source", "is_mock")) {
            if (meta.containsKey(key)) result.put(key, meta.get(key));
        }
        result.put("engineParameters", meta.containsKey("engine_info") ? map(meta.get("engine_info")) : engine);
        return result;
    }

    private void addSource(Map<String, Object> output, String source) {
        if (source == null || source.isBlank()) {
            output.put("strategySourceAvailable", false);
            output.put("strategySourceReason", "该回测创建时尚未持久化源码，且运行目录可能已清理");
        } else if (source.length() > MAX_SOURCE_CHARS) {
            output.put("strategySourceAvailable", false);
            output.put("strategySourceReason", "策略源码超过模型安全上下文上限，未发送截断代码");
        } else {
            output.put("strategySourceAvailable", true);
            output.put("strategySource", source);
        }
    }

    private String boundedJson(Map<String, Object> output, boolean includeSource) {
        String value = json(output);
        if (value.length() <= MAX_MODEL_JSON_CHARS) return value;
        output.put("trajectory", trajectory(mapFromOutput(output), 100));
        value = json(output);
        if (value.length() <= MAX_MODEL_JSON_CHARS) return value;
        if (includeSource && output.remove("strategySource") != null) {
            output.put("strategySourceAvailable", false);
            output.put("strategySourceReason", "组合上下文超过模型上限，源码未发送");
            value = json(output);
        }
        if (value.length() > MAX_MODEL_JSON_CHARS) {
            output.remove("execution");
            value = json(output);
        }
        if (value.length() > MAX_MODEL_JSON_CHARS) {
            Map<String, Object> oldTrajectory = map(output.get("trajectory"));
            output.put("trajectory", Map.of(
                    "originalPointCount", oldTrajectory.getOrDefault("originalPointCount", 0),
                    "omittedForModel", true));
            value = json(output);
        }
        if (value.length() > MAX_MODEL_JSON_CHARS) {
            output.put("metrics", Map.of("omittedForModel", true, "reason", "指标集合超过上下文上限"));
            value = json(output);
        }
        if (value.length() > MAX_MODEL_JSON_CHARS)
            throw new BusinessException(413103, "回测上下文超过模型上限", HttpStatus.PAYLOAD_TOO_LARGE);
        return value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }
    private Map<String, Object> mapFromOutput(Map<String, Object> output) {
        Map<String, Object> trajectory = map(output.get("trajectory"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dates", trajectory.get("dates")); data.put("strategy", trajectory.get("strategyReturnPct"));
        data.put("benchmark", trajectory.get("benchmarkReturnPct")); data.put("excess", trajectory.get("excessReturnPct"));
        return data;
    }
    private List<?> list(Object value) { return value instanceof List<?> values ? values : List.of(); }
    private List<Integer> indexes(int size, int max) {
        if (size <= 0) return List.of();
        if (size <= max) { List<Integer> all = new ArrayList<>(size); for (int i=0;i<size;i++) all.add(i); return all; }
        List<Integer> sampled = new ArrayList<>(max);
        for (int i=0;i<max;i++) sampled.add((int) Math.round(i * (size - 1.0) / (max - 1.0)));
        return sampled;
    }
    private List<Object> pick(List<?> values, List<Integer> indexes) {
        List<Object> result = new ArrayList<>(indexes.size());
        for (int index : indexes) result.add(values.get(index));
        return result;
    }
    private String json(Map<String, Object> value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw new BusinessException(500103, "回测上下文生成失败", HttpStatus.INTERNAL_SERVER_ERROR); }
    }
    private String text(Object value) { return value == null ? null : value.toString(); }

    private record TaskRow(long id, String status, int progress, String stage,
                           LocalDateTime createdAt, LocalDateTime finishedAt) {}
    private record RunRow(String strategyFilename, String engineType, String benchmarkSymbol,
                          LocalDate startDate, LocalDate endDate, String resultJson, String strategySource) {}
}
