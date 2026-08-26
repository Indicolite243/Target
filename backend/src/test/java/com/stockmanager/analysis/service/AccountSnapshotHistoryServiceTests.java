package com.stockmanager.analysis.service;

import com.stockmanager.account.entity.AccountHistorySnapshot;
import com.stockmanager.account.mapper.AccountHistorySnapshotMapper;
import com.stockmanager.account.mapper.PositionHistorySnapshotMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountSnapshotHistoryServiceTests {
    @Test
    void readsPortfolioCurveFromMysqlSnapshots() {
        AccountHistorySnapshotMapper accountMapper = mock(AccountHistorySnapshotMapper.class);
        PositionHistorySnapshotMapper positionMapper = mock(PositionHistorySnapshotMapper.class);
        LocalDateTime firstTime = LocalDateTime.of(2026, 8, 1, 15, 10);
        LocalDateTime lastTime = LocalDateTime.of(2026, 8, 2, 15, 10);
        AccountHistorySnapshot first = snapshot(1L, firstTime, "100000");
        AccountHistorySnapshot last = snapshot(2L, lastTime, "101500");
        when(accountMapper.selectList(any())).thenReturn(List.of(first, last));
        when(accountMapper.findLatestWithPositionsAtOrBefore(any(), any())).thenReturn(null);
        when(accountMapper.findFirstWithPositionsAtOrAfter(any(), any())).thenReturn(null);

        Map<String, Object> result = new AccountSnapshotHistoryService(accountMapper, positionMapper)
                .portfolioHistory(7L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2), "DAILY");

        assertThat(result.get("source")).isEqualTo("mysql_account_snapshots");
        assertThat((List<?>) result.get("portfolioValues")).hasSize(2);
        assertThat((List<?>) result.get("warnings")).anyMatch(String.class::isInstance)
                .noneMatch(value -> String.valueOf(value).contains("MongoDB"));
    }

    private AccountHistorySnapshot snapshot(Long id, LocalDateTime time, String totalAsset) {
        AccountHistorySnapshot snapshot = new AccountHistorySnapshot();
        snapshot.setId(id);
        snapshot.setSnapshotTime(time);
        snapshot.setTotalAsset(new BigDecimal(totalAsset));
        return snapshot;
    }
}
