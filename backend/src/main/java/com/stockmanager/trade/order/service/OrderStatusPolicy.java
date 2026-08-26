package com.stockmanager.trade.order.service;

import java.util.Set;

/** Centralized order-state rules keep request, scheduler and persistence paths consistent. */
public final class OrderStatusPolicy {
    private static final Set<String> TERMINAL = Set.of("FILLED", "CANCELED", "REJECTED", "FAILED");
    private static final Set<String> TRACKABLE = Set.of("PENDING_SUBMIT", "SUBMITTED", "PARTIALLY_FILLED",
            "CANCEL_PENDING", "UNKNOWN");
    private static final Set<String> CANCELABLE = Set.of("SUBMITTED", "PARTIALLY_FILLED", "UNKNOWN");
    private static final Set<String> LEGACY_TRACKABLE = Set.of(
            "UNREPORTED", "WAIT_REPORTING", "REPORTED", "REPORTED_CANCEL",
            "PARTIALLY_FILLED_CANCEL_PENDING");

    private OrderStatusPolicy() {
    }

    public static boolean isTerminal(String status) {
        return TERMINAL.contains(canonicalizeBrokerStatus(status, "UNKNOWN"));
    }

    public static boolean isTrackable(String status) {
        return TRACKABLE.contains(canonicalizeBrokerStatus(status, "UNKNOWN"));
    }

    public static boolean canCancel(String status) {
        return CANCELABLE.contains(canonicalizeBrokerStatus(status, "UNKNOWN"));
    }

    /**
     * Converts QMT's broker status names to the application's lifecycle states.
     * The raw QMT status is retained in audit details; the database state itself
     * must stay canonical so polling, cancellation, and terminal-state checks
     * all use one vocabulary.
     */
    public static String canonicalizeBrokerStatus(String rawStatus, String fallback) {
        String status = rawStatus == null || rawStatus.isBlank() ? fallback : rawStatus;
        if (status == null || status.isBlank()) return "UNKNOWN";
        return switch (status.trim().toUpperCase()) {
            case "UNREPORTED", "WAIT_REPORTING", "PENDING_SUBMIT" -> "PENDING_SUBMIT";
            case "REPORTED", "SUBMITTED" -> "SUBMITTED";
            case "REPORTED_CANCEL", "PARTIALLY_FILLED_CANCEL_PENDING", "CANCEL_PENDING" -> "CANCEL_PENDING";
            case "PARTIALLY_FILLED" -> "PARTIALLY_FILLED";
            case "PARTIALLY_CANCELED", "CANCELED" -> "CANCELED";
            case "FILLED", "REJECTED", "FAILED", "UNKNOWN" -> status.trim().toUpperCase();
            default -> "UNKNOWN";
        };
    }

    /** Includes legacy raw QMT values so orders created before canonicalization recover on the next poll. */
    public static Set<String> trackableStoredStates() {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>(TRACKABLE);
        result.addAll(LEGACY_TRACKABLE);
        return Set.copyOf(result);
    }
}
