package com.stockmanager.analysis.service.impl;

import com.stockmanager.account.entity.Account;
import com.stockmanager.account.entity.Position;
import com.stockmanager.account.mapper.AccountMapper;
import com.stockmanager.account.mapper.PositionMapper;
import com.stockmanager.analysis.service.AccountSnapshotHistoryService;
import com.stockmanager.integration.quant.QuantClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalysisServiceImplTests {
    @Test
    void historicalAttributionBackfillsIndustryAndExplainsQuantityEffect() {
        AccountMapper accountMapper = mock(AccountMapper.class);
        PositionMapper positionMapper = mock(PositionMapper.class);
        AccountSnapshotHistoryService historyService = mock(AccountSnapshotHistoryService.class);
        QuantClient quantClient = mock(QuantClient.class);

        Account account = new Account();
        account.setId(7L);
        account.setUserId(42L);
        when(accountMapper.selectOne(any())).thenReturn(account);

        Position current = new Position();
        current.setSecurityCode("600941.SH");
        current.setSecurityName("中国移动");
        current.setIndustry(null);
        when(positionMapper.selectList(any())).thenReturn(List.of(current));

        when(historyService.portfolioHistory(any(), any(), any(), any())).thenReturn(Map.of(
                "positionHistory", List.of(Map.of(
                        "symbol", "600941.SH",
                        "securityName", "中国移动",
                        "industry", "其他",
                        "marketValue", "120.00",
                        "periodPnl", "20.00",
                        "startPrice", "10.00",
                        "endPrice", "9.00",
                        "costPrice", "8.00",
                        "quantity", "10")),
                "source", "mysql_account_snapshots",
                "rangeStart", "2026-08-12",
                "rangeEnd", "2026-09-04",
                "tradingDays", 18,
                "warnings", List.of()));

        Map<String, Object> result = new AnalysisServiceImpl(
                accountMapper, positionMapper, historyService, quantClient)
                .attribution(42L, 7L, "INDUSTRY", "mysql", null, null, "trace-1");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stocks = (List<Map<String, Object>>) result.get("attributionRows");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> industries = (List<Map<String, Object>>) result.get("industryRows");
        assertThat(stocks.getFirst().get("industry")).isEqualTo("通信");
        assertThat(industries.getFirst().get("name")).isEqualTo("通信");
        assertThat(String.valueOf(result.get("calculation_method"))).contains("持仓数量变化");
        assertThat((List<?>) result.get("warnings"))
                .anyMatch(value -> String.valueOf(value).contains("方向不同"));
    }
}
