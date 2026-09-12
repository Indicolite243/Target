package com.stockmanager.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantBacktestToolServiceTests {
    @Test
    void returnsDownsampledFullRangeWithoutSourceForAnalysisTool() throws Exception {
        JdbcTemplate jdbc = successfulJdbc("def init(context):\n    pass\n");
        AssistantBacktestToolService.SelectedBacktest result =
                new AssistantBacktestToolService(jdbc, new ObjectMapper()).getOrSelect(42L, "conversation-1", false);

        assertThat(result.taskId()).isEqualTo(9L);
        assertThat(result.modelJson())
                .contains("\"originalPointCount\":500", "\"sampledPointCount\":240", "\"downsampled\":true")
                .contains("\"benchmark_symbol\":\"510300.SH\"", "不能视为指数精确回测")
                .doesNotContain("strategySource", "def init");
        verify(jdbc).update(contains("active_backtest_task_id"), any(Object[].class));
    }

    @Test
    void sendsPersistedSourceOnlyWhenSourceToolWasRequested() throws Exception {
        JdbcTemplate jdbc = successfulJdbc("def init(context):\n    pass\n");
        AssistantBacktestToolService.SelectedBacktest result =
                new AssistantBacktestToolService(jdbc, new ObjectMapper()).getOrSelect(42L, "conversation-1", true);

        assertThat(result.modelJson()).contains("\"strategySourceAvailable\":true", "def init(context)");
    }

    @Test
    void reportsLatestFailureWithoutFallingBackToAnOlderSuccess() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(contains("active_backtest_task_id"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbc.query(contains("FROM quant_task"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> List.of(invocation.<RowMapper<?>>getArgument(1).mapRow(taskRow("FAILED"), 0)));

        AssistantBacktestToolService.SelectedBacktest result =
                new AssistantBacktestToolService(jdbc, new ObjectMapper()).getOrSelect(42L, "conversation-1", false);

        assertThat(result.modelJson()).contains("最近一次回测执行失败", "不回退到更早的成功结果");
        verify(jdbc, never()).query(contains("FROM backtest_run"), any(RowMapper.class), any(Object[].class));
    }

    private JdbcTemplate successfulJdbc(String source) throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(contains("active_backtest_task_id"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbc.query(contains("FROM quant_task"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> List.of(invocation.<RowMapper<?>>getArgument(1).mapRow(taskRow("SUCCEEDED"), 0)));
        when(jdbc.query(contains("FROM backtest_run"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> List.of(invocation.<RowMapper<?>>getArgument(1).mapRow(runRow(source), 0)));
        return jdbc;
    }

    private ResultSet taskRow(String status) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(9L);
        when(row.getString("status")).thenReturn(status);
        when(row.getInt("progress")).thenReturn("SUCCEEDED".equals(status) ? 100 : 30);
        when(row.getString("stage")).thenReturn("SUCCEEDED".equals(status) ? "回测完成" : "执行失败");
        when(row.getObject("created_at", LocalDateTime.class)).thenReturn(LocalDateTime.of(2026, 9, 1, 10, 0));
        when(row.getObject("finished_at", LocalDateTime.class)).thenReturn(LocalDateTime.of(2026, 9, 1, 10, 5));
        return row;
    }

    private ResultSet runRow(String source) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("strategy_filename")).thenReturn("strategy.py");
        when(row.getString("engine_type")).thenReturn("mindgo");
        when(row.getString("benchmark_symbol")).thenReturn("000300.SH");
        when(row.getObject("start_date", LocalDate.class)).thenReturn(LocalDate.of(2024, 1, 1));
        when(row.getObject("end_date", LocalDate.class)).thenReturn(LocalDate.of(2025, 12, 31));
        when(row.getString("strategy_source")).thenReturn(source);
        List<Integer> series = IntStream.range(0, 500).boxed().toList();
        Map<String, Object> data = Map.of(
                "dates", series, "strategy", series, "benchmark", series, "excess", series,
                "metrics", Map.of("sharpe_ratio", "1.20", "max_drawdown", "12.00%"),
                "execution_meta", Map.of(
                        "benchmark_symbol_requested", "000300.SH",
                        "benchmark_symbol", "510300.SH",
                        "benchmark_data_source", "local_proxy",
                        "benchmark_warning", "不能视为指数精确回测"));
        when(row.getString("result_json")).thenReturn(new ObjectMapper().writeValueAsString(Map.of("data", data)));
        return row;
    }
}
