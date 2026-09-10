package com.stockmanager.trade.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmanager.trade.order.entity.TradeOrder;
import com.stockmanager.trade.order.entity.TradeOrderAudit;
import com.stockmanager.trade.order.entity.TradeOrderStatusHistory;
import com.stockmanager.trade.order.mapper.TradeOrderAuditMapper;
import com.stockmanager.trade.order.mapper.TradeOrderMapper;
import com.stockmanager.trade.order.mapper.TradeOrderStatusHistoryMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderStatePersistenceServiceTests {
    @Test
    void staleReportedObservationDoesNotUndoPendingCancellation() {
        Fixture fixture = fixture(order("CANCEL_PENDING", LocalDateTime.of(2026, 8, 27, 19, 36)));

        boolean changed = fixture.service.applyBrokerStatus(1L, Map.of(
                "status", "REPORTED",
                "tradedQuantity", "0",
                "tradedPrice", "0"
        ), "test-poller");

        assertFalse(changed);
        assertEquals("CANCEL_PENDING", fixture.order.getStatus());
        verify(fixture.orderMapper, never()).updateById(any(TradeOrder.class));
        verify(fixture.historyMapper, never()).insert(any(TradeOrderStatusHistory.class));
        verify(fixture.auditMapper, never()).insert(any(TradeOrderAudit.class));
    }

    @Test
    void explicitBrokerTerminalStateStillEndsPendingCancellation() {
        Fixture fixture = fixture(order("CANCEL_PENDING", LocalDateTime.of(2026, 8, 27, 19, 36)));

        boolean changed = fixture.service.applyBrokerStatus(1L, Map.of(
                "status", "PARTIALLY_CANCELED",
                "tradedQuantity", "0",
                "tradedPrice", "0"
        ), "test-poller");

        assertTrue(changed);
        assertEquals("CANCELED", fixture.order.getStatus());
        verify(fixture.orderMapper).updateById(fixture.order);
        verify(fixture.historyMapper).insert(any(TradeOrderStatusHistory.class));
        verify(fixture.auditMapper).insert(any(TradeOrderAudit.class));
    }

    @Test
    void missingPriorDayCancellationClosesAndBecomesDeletable() {
        Fixture fixture = fixture(order("CANCEL_PENDING", LocalDateTime.of(2026, 8, 27, 19, 36)));

        boolean changed = fixture.service.reconcileMissingPriorDayCancellation(
                1L, LocalDate.of(2026, 8, 28), "test-poller");

        assertTrue(changed);
        assertEquals("CANCELED", fixture.order.getStatus());
        assertTrue(OrderStatusPolicy.isTerminal(fixture.order.getStatus()));
        verify(fixture.orderMapper).updateById(fixture.order);
        verify(fixture.historyMapper).insert(any(TradeOrderStatusHistory.class));
        verify(fixture.auditMapper).insert(any(TradeOrderAudit.class));
    }

    private Fixture fixture(TradeOrder order) {
        TradeOrderMapper orderMapper = mock(TradeOrderMapper.class);
        TradeOrderStatusHistoryMapper historyMapper = mock(TradeOrderStatusHistoryMapper.class);
        TradeOrderAuditMapper auditMapper = mock(TradeOrderAuditMapper.class);
        when(orderMapper.selectById(1L)).thenReturn(order);
        OrderStatePersistenceService service = new OrderStatePersistenceService(
                orderMapper, historyMapper, auditMapper, new ObjectMapper());
        return new Fixture(service, orderMapper, historyMapper, auditMapper, order);
    }

    private TradeOrder order(String status, LocalDateTime createdAt) {
        TradeOrder order = new TradeOrder();
        order.setId(1L);
        order.setUserId(2L);
        order.setStatus(status);
        order.setSecurityName("测试证券");
        order.setQuantity(new BigDecimal("200"));
        order.setFilledQuantity(BigDecimal.ZERO);
        order.setAverageFilledPrice(BigDecimal.ZERO);
        order.setCreatedAt(createdAt);
        order.setUpdatedAt(createdAt);
        return order;
    }

    private record Fixture(OrderStatePersistenceService service,
                           TradeOrderMapper orderMapper,
                           TradeOrderStatusHistoryMapper historyMapper,
                           TradeOrderAuditMapper auditMapper,
                           TradeOrder order) {
    }
}
