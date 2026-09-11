package com.stockmanager.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDateTime;
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

class AssistantPortfolioSnapshotServiceTests {
    @Test
    void freezesOnlyAnalysisFieldsAndNeverExportsAccountIdentifiers() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(jdbc.query(contains("FROM account"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> List.of(invocation.<RowMapper<?>>getArgument(1)
                        .mapRow(accountRow(), 0)));
        when(jdbc.query(contains("FROM position"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> List.of(invocation.<RowMapper<?>>getArgument(1)
                        .mapRow(positionRow(), 0)));

        AssistantPortfolioSnapshotService.FrozenSnapshot snapshot =
                new AssistantPortfolioSnapshotService(jdbc, new ObjectMapper()).getOrCreate(42L, "conversation-1");

        assertThat(snapshot.modelJson())
                .contains("600000.SH", "top1Weight", "MYSQL_LATEST_CONFIRMED_SNAPSHOT",
                        "\"sourceDataAsOf\":\"2026-09-11T08:30\"")
                .doesNotContain("62283925", "accountNo", "accountId", "userId", "私密账户名称");
        verify(jdbc).update(contains("INSERT INTO ai_data_snapshot"), any(Object[].class));
    }

    @Test
    void reusesConversationSnapshotWithoutReadingCurrentAccountAgain() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "id", "snapshot-1", "snapshot_json", "{\"available\":true}")));

        AssistantPortfolioSnapshotService.FrozenSnapshot snapshot =
                new AssistantPortfolioSnapshotService(jdbc, new ObjectMapper()).getOrCreate(42L, "conversation-1");

        assertThat(snapshot.id()).isEqualTo("snapshot-1");
        assertThat(snapshot.modelJson()).isEqualTo("{\"available\":true}");
        verify(jdbc, never()).query(contains("FROM account"), any(RowMapper.class), any(Object[].class));
    }

    private ResultSet accountRow() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(7L);
        when(row.getString("broker")).thenReturn("QMT");
        when(row.getString("environment")).thenReturn("SIMULATION");
        when(row.getString("currency")).thenReturn("CNY");
        when(row.getBigDecimal("total_asset")).thenReturn(new BigDecimal("100000"));
        when(row.getBigDecimal("cash")).thenReturn(new BigDecimal("30000"));
        when(row.getBigDecimal("market_value")).thenReturn(new BigDecimal("70000"));
        when(row.getBigDecimal("profit_loss")).thenReturn(new BigDecimal("1000"));
        when(row.getObject("last_sync_time", LocalDateTime.class))
                .thenReturn(LocalDateTime.of(2026, 9, 11, 8, 30));
        when(row.getObject("data_version")).thenReturn(9L);
        when(row.getLong("data_version")).thenReturn(9L);
        return row;
    }

    private ResultSet positionRow() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("security_code")).thenReturn("600000.SH");
        when(row.getString("security_name")).thenReturn("浦发银行");
        when(row.getBigDecimal("quantity")).thenReturn(new BigDecimal("100"));
        when(row.getBigDecimal("available_quantity")).thenReturn(new BigDecimal("100"));
        when(row.getBigDecimal("cost_price")).thenReturn(new BigDecimal("10"));
        when(row.getBigDecimal("last_price")).thenReturn(new BigDecimal("11"));
        when(row.getBigDecimal("market_value")).thenReturn(new BigDecimal("1100"));
        when(row.getBigDecimal("profit_loss")).thenReturn(new BigDecimal("100"));
        when(row.getString("industry")).thenReturn("银行");
        when(row.getString("region")).thenReturn("上海市场");
        return row;
    }
}
