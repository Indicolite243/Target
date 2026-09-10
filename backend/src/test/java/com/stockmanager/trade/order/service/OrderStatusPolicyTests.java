package com.stockmanager.trade.order.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderStatusPolicyTests {
    @Test
    void onlyUnfinishedBrokerStatesAreTracked() {
        assertTrue(OrderStatusPolicy.isTrackable("SUBMITTED"));
        assertTrue(OrderStatusPolicy.isTrackable("REPORTED"));
        assertTrue(OrderStatusPolicy.isTrackable("UNKNOWN"));
        assertFalse(OrderStatusPolicy.isTrackable("FILLED"));
        assertFalse(OrderStatusPolicy.isTrackable("REJECTED"));
    }

    @Test
    void cancellationDoesNotRetryWhilePendingOrAfterTerminalState() {
        assertTrue(OrderStatusPolicy.canCancel("SUBMITTED"));
        assertTrue(OrderStatusPolicy.canCancel("REPORTED"));
        assertTrue(OrderStatusPolicy.canCancel("PARTIALLY_FILLED"));
        assertFalse(OrderStatusPolicy.canCancel("CANCEL_PENDING"));
        assertFalse(OrderStatusPolicy.canCancel("CANCELED"));
        assertTrue(OrderStatusPolicy.isTerminal("FILLED"));
    }

    @Test
    void qmtBrokerStatusesUseCanonicalLifecycleStates() {
        assertEquals("SUBMITTED", OrderStatusPolicy.canonicalizeBrokerStatus("REPORTED", "UNKNOWN"));
        assertEquals("CANCEL_PENDING", OrderStatusPolicy.canonicalizeBrokerStatus(
                "PARTIALLY_FILLED_CANCEL_PENDING", "UNKNOWN"));
        assertEquals("CANCELED", OrderStatusPolicy.canonicalizeBrokerStatus("PARTIALLY_CANCELED", "UNKNOWN"));
        assertEquals("FILLED", OrderStatusPolicy.canonicalizeBrokerStatus("FILLED", "UNKNOWN"));
    }

    @Test
    void pendingCancellationCannotRegressToStaleReportedState() {
        assertEquals("CANCEL_PENDING",
                OrderStatusPolicy.resolveBrokerObservation("CANCEL_PENDING", "REPORTED"));
        assertEquals("CANCEL_PENDING",
                OrderStatusPolicy.resolveBrokerObservation("CANCEL_PENDING", "PARTIALLY_FILLED"));
        assertEquals("CANCELED",
                OrderStatusPolicy.resolveBrokerObservation("CANCEL_PENDING", "PARTIALLY_CANCELED"));
        assertEquals("FILLED",
                OrderStatusPolicy.resolveBrokerObservation("CANCEL_PENDING", "FILLED"));
    }

    @Test
    void onlyPriorDayPendingCancellationMayReconcileWhenMissing() {
        LocalDate today = LocalDate.of(2026, 8, 28);
        assertTrue(OrderStatusPolicy.shouldReconcileMissingPriorDayCancellation(
                "CANCEL_PENDING", LocalDateTime.of(2026, 8, 27, 20, 42), today));
        assertFalse(OrderStatusPolicy.shouldReconcileMissingPriorDayCancellation(
                "SUBMITTED", LocalDateTime.of(2026, 8, 27, 20, 42), today));
        assertFalse(OrderStatusPolicy.shouldReconcileMissingPriorDayCancellation(
                "CANCEL_PENDING", LocalDateTime.of(2026, 8, 28, 8, 50), today));
    }
}
