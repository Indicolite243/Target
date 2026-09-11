package com.stockmanager.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmanager.analysis.service.AnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantAttributionToolServiceTests {
    @Test
    void freezesCurrentUsersThirtyDayMysqlAttributionWithoutIdentifiers() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AnalysisService analysis = mock(AnalysisService.class);
        when(jdbc.queryForList(contains("active_attribution_snapshot_id"), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbc.queryForList(contains("FROM account"), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 7L, "data_version", 19L)));
        when(analysis.attribution(42L, 7L, "INDUSTRY", "mysql", null, null, "trace-1"))
                .thenReturn(Map.of(
                        "data_source", "mysql_snapshot",
                        "range_start", "2026-08-12",
                        "range_end", "2026-09-11",
                        "sample_count", 21,
                        "summary", Map.of("totalPnlAmount", 1234, "leadingIndustry", "医药"),
                        "attributionRows", List.of(Map.of("stockCode", "300760.SZ",
                                "stockName", "迈瑞医疗", "contributionPct", 0.0437)),
                        "industryRows", List.of(Map.of("name", "医药", "contributionPct", 0.0437)),
                        "calculation_method", "历史持仓快照区间变化",
                        "warnings", List.of()));

        AssistantAttributionToolService.FrozenAttribution result =
                new AssistantAttributionToolService(jdbc, new ObjectMapper(), analysis)
                        .getOrCreate(42L, "conversation-1", "trace-1");

        assertThat(result.modelJson())
                .contains("PERFORMANCE_ATTRIBUTION", "300760.SZ", "迈瑞医疗", "医药",
                        "2026-08-12", "2026-09-11", "不是完整Brinson")
                .doesNotContain("accountId", "userId", "conversation-1");
        verify(analysis).attribution(42L, 7L, "INDUSTRY", "mysql", null, null, "trace-1");
        verify(jdbc).update(contains("INSERT INTO ai_data_snapshot"), any(Object[].class));
        verify(jdbc).update(contains("active_attribution_snapshot_id"), any(Object[].class));
    }

    @Test
    void reusesFrozenAttributionWithoutRecalculating() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AnalysisService analysis = mock(AnalysisService.class);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "id", "attribution-1", "snapshot_json", "{\"available\":true}")));

        AssistantAttributionToolService.FrozenAttribution result =
                new AssistantAttributionToolService(jdbc, new ObjectMapper(), analysis)
                        .getOrCreate(42L, "conversation-1", "trace-1");

        assertThat(result.id()).isEqualTo("attribution-1");
        assertThat(result.modelJson()).isEqualTo("{\"available\":true}");
        verify(analysis, never()).attribution(any(), any(), anyString(), anyString(), any(), any(), anyString());
    }
}
