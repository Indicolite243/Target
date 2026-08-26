package com.stockmanager.account.live;

import com.stockmanager.account.entity.Account;
import com.stockmanager.account.entity.Position;
import com.stockmanager.account.mapper.AccountMapper;
import com.stockmanager.account.mapper.PositionMapper;
import com.stockmanager.account.vo.AccountView;
import com.stockmanager.account.vo.PositionView;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class PortfolioLivePersistenceServiceTests {
    @Test
    void replacesCurrentPositionsWithOneBatchInsert() {
        AccountMapper accountMapper = mock(AccountMapper.class);
        PositionMapper positionMapper = mock(PositionMapper.class);
        Account account = new Account();
        account.setId(7L);
        account.setAccountNo("62283925");
        when(accountMapper.selectById(7L)).thenReturn(account);

        CurrentPortfolioSnapshot snapshot = new CurrentPortfolioSnapshot("7", 9L, "QMT", "LIVE", false,
                LocalDateTime.of(2026, 8, 26, 10, 0), 0L,
                new AccountView("7", "62283925", "****3925", "QMT账户", "GUOJIN_QMT", "SIMULATION", "CNY",
                        "100000", "30000", "70000", "0", LocalDateTime.of(2026, 8, 26, 10, 0), false, "ACTIVE"),
                List.of(new PositionView(null, "600000.SH", "浦发银行", "100", "100", "10", "10.5", "1050",
                                "50", "银行", "上海市场"),
                        new PositionView(null, "000001.SZ", "平安银行", "200", "200", "9", "9.2", "1840",
                                "40", "银行", "深圳市场")),
                List.of());

        new PortfolioLivePersistenceService(accountMapper, positionMapper).persistCurrentState(7L, snapshot);

        verify(positionMapper).delete(any());
        verify(positionMapper).insertBatch(argThat(rows -> rows.size() == 2
                && rows.stream().allMatch(row -> row.getId() != null)));
        verify(positionMapper, never()).insert(any(Position.class));
    }
}
