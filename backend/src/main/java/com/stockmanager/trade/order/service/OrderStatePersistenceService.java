package com.stockmanager.trade.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmanager.trade.order.entity.TradeOrder;
import com.stockmanager.trade.order.entity.TradeOrderAudit;
import com.stockmanager.trade.order.entity.TradeOrderStatusHistory;
import com.stockmanager.trade.order.mapper.TradeOrderAuditMapper;
import com.stockmanager.trade.order.mapper.TradeOrderMapper;
import com.stockmanager.trade.order.mapper.TradeOrderStatusHistoryMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Contains only short MySQL transactions. QMT calls must be made by the caller
 * before or after entering this component, never while a database transaction is open.
 */
@Service
public class OrderStatePersistenceService {
    private static final DateTimeFormatter ORDER_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final TradeOrderMapper orderMapper;
    private final TradeOrderStatusHistoryMapper historyMapper;
    private final TradeOrderAuditMapper auditMapper;
    private final ObjectMapper objectMapper;

    public OrderStatePersistenceService(TradeOrderMapper orderMapper, TradeOrderStatusHistoryMapper historyMapper,
                                        TradeOrderAuditMapper auditMapper, ObjectMapper objectMapper) {
        this.orderMapper = orderMapper;
        this.historyMapper = historyMapper;
        this.auditMapper = auditMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PendingOrder createPending(TradeOrder order, String traceId) {
        TradeOrder existing = findByIdempotency(order.getUserId(), order.getIdempotencyKey());
        if (existing != null) return new PendingOrder(existing, false);

        LocalDateTime now = LocalDateTime.now();
        order.setClientOrderNo("ORD-" + now.format(ORDER_TIME) + "-" + UUID.randomUUID().toString().substring(0, 8));
        order.setFilledQuantity(BigDecimal.ZERO);
        order.setStatus("PENDING_SUBMIT");
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException ex) {
            TradeOrder raced = findByIdempotency(order.getUserId(), order.getIdempotencyKey());
            if (raced != null) return new PendingOrder(raced, false);
            throw ex;
        }
        appendStatusHistory(order, null, "API", "等待向QMT提交", now);
        appendAudit(order, "SUBMIT_REQUESTED", order.getIdempotencyKey(), "API", Map.of(
                "traceId", safe(traceId), "clientOrderNo", order.getClientOrderNo(), "status", order.getStatus()), now);
        return new PendingOrder(order, true);
    }

    @Transactional
    public TradeOrder applySubmitAccepted(Long orderId, Map<String, Object> result, String traceId) {
        TradeOrder order = require(orderId);
        String rawStatus = status(result.get("status"), "SUBMITTED");
        String status = OrderStatusPolicy.canonicalizeBrokerStatus(rawStatus, "SUBMITTED");
        String externalOrderNo = nonBlank(result.get("externalOrderNo"), order.getExternalOrderNo());
        String message = nonBlank(result.get("statusMessage"), null);
        updateOrder(order, status, externalOrderNo, null, null, null, "QMT_SUBMIT", message);
        appendAudit(order, "SUBMIT_ACCEPTED", null, "QMT_SUBMIT", Map.of(
                "traceId", safe(traceId), "externalOrderNo", safe(externalOrderNo),
                "rawBrokerStatus", rawStatus, "status", status), LocalDateTime.now());
        return order;
    }

    @Transactional
    public TradeOrder markSubmitUnknown(Long orderId, String traceId, String reason) {
        TradeOrder order = require(orderId);
        updateOrder(order, "UNKNOWN", null, null, null, null, "QMT_SUBMIT", reason);
        appendAudit(order, "SUBMIT_UNKNOWN", null, "QMT_SUBMIT", Map.of(
                "traceId", safe(traceId), "reason", safe(reason)), LocalDateTime.now());
        return order;
    }

    @Transactional
    public TradeOrder markSubmitRejected(Long orderId, String traceId, String reason) {
        TradeOrder order = require(orderId);
        updateOrder(order, "REJECTED", null, null, null, null, "QMT_SUBMIT", reason);
        appendAudit(order, "SUBMIT_REJECTED", null, "QMT_SUBMIT", Map.of(
                "traceId", safe(traceId), "reason", safe(reason)), LocalDateTime.now());
        return order;
    }

    @Transactional
    public CancelRequest requestCancel(Long orderId, String idempotencyKey, String traceId) {
        TradeOrder order = require(orderId);
        TradeOrderAudit duplicate = auditMapper.selectOne(Wrappers.<TradeOrderAudit>lambdaQuery()
                .eq(TradeOrderAudit::getOrderId, orderId)
                .eq(TradeOrderAudit::getAction, "CANCEL_REQUESTED")
                .eq(TradeOrderAudit::getIdempotencyKey, idempotencyKey));
        if (duplicate != null) return new CancelRequest(order, false, true);
        if (!OrderStatusPolicy.canCancel(order.getStatus())) return new CancelRequest(order, false, false);

        updateOrder(order, "CANCEL_PENDING", null, null, null, null, "API", "等待QMT撤单确认");
        appendAudit(order, "CANCEL_REQUESTED", idempotencyKey, "API", Map.of(
                "traceId", safe(traceId), "status", order.getStatus()), LocalDateTime.now());
        return new CancelRequest(order, true, false);
    }

    @Transactional
    public TradeOrder applyCancelAccepted(Long orderId, Map<String, Object> result, String traceId) {
        TradeOrder order = require(orderId);
        String rawStatus = status(result.get("status"), "CANCELED");
        String status = OrderStatusPolicy.canonicalizeBrokerStatus(rawStatus, "CANCELED");
        String message = nonBlank(result.get("statusMessage"), null);
        updateOrder(order, status, null, null, null, null, "QMT_CANCEL", message);
        appendAudit(order, "CANCEL_ACCEPTED", null, "QMT_CANCEL", Map.of(
                "traceId", safe(traceId), "rawBrokerStatus", rawStatus, "status", status), LocalDateTime.now());
        return order;
    }

    @Transactional
    public TradeOrder markCancelUnconfirmed(Long orderId, String traceId, String reason) {
        TradeOrder order = require(orderId);
        appendAudit(order, "CANCEL_UNCONFIRMED", null, "QMT_CANCEL", Map.of(
                "traceId", safe(traceId), "reason", safe(reason)), LocalDateTime.now());
        return order;
    }

    /** Applies an externally observed broker state only when it changed, avoiding needless write amplification. */
    @Transactional
    public boolean applyBrokerStatus(Long orderId, Map<?, ?> row, String traceId) {
        TradeOrder order = orderMapper.selectById(orderId);
        if (order == null || OrderStatusPolicy.isTerminal(order.getStatus())) return false;
        String rawStatus = nonBlank(row.get("status"), order.getStatus());
        String status = OrderStatusPolicy.canonicalizeBrokerStatus(rawStatus, order.getStatus());
        BigDecimal filled = decimal(row.get("tradedQuantity"));
        BigDecimal average = decimal(row.get("tradedPrice"));
        String securityName = nonBlank(row.get("securityName"), order.getSecurityName());
        String message = nonBlank(row.get("statusMessage"), null);
        boolean changed = updateOrder(order, status, null, filled, average, securityName,
                "QMT_STATUS_POLL", message);
        if (changed) {
            appendAudit(order, "STATUS_CHANGED", null, "QMT_STATUS_POLL", Map.of(
                    "traceId", safe(traceId), "rawBrokerStatus", rawStatus, "status", order.getStatus(),
                    "filledQuantity", safe(decimalText(order.getFilledQuantity()))), LocalDateTime.now());
        }
        return changed;
    }

    public TradeOrder findByIdempotency(Long userId, String idempotencyKey) {
        return orderMapper.selectOne(Wrappers.<TradeOrder>lambdaQuery()
                .eq(TradeOrder::getUserId, userId).eq(TradeOrder::getIdempotencyKey, idempotencyKey));
    }

    private boolean updateOrder(TradeOrder order, String targetStatus, String externalOrderNo,
                                BigDecimal filledQuantity, BigDecimal averageFilledPrice, String securityName,
                                String source, String brokerMessage) {
        String previousStatus = order.getStatus();
        BigDecimal previousFilled = order.getFilledQuantity();
        BigDecimal previousAverage = order.getAverageFilledPrice();
        boolean statusChanged = !Objects.equals(previousStatus, targetStatus);
        boolean filledChanged = filledQuantity != null && !sameDecimal(previousFilled, filledQuantity);
        boolean averageChanged = averageFilledPrice != null && !sameDecimal(previousAverage, averageFilledPrice);
        boolean externalChanged = externalOrderNo != null && !Objects.equals(order.getExternalOrderNo(), externalOrderNo);
        boolean nameChanged = securityName != null && !Objects.equals(order.getSecurityName(), securityName);
        if (!statusChanged && !filledChanged && !averageChanged && !externalChanged && !nameChanged) return false;

        if (targetStatus != null) order.setStatus(targetStatus);
        if (externalOrderNo != null) order.setExternalOrderNo(externalOrderNo);
        if (filledQuantity != null) order.setFilledQuantity(filledQuantity);
        if (averageFilledPrice != null) order.setAverageFilledPrice(averageFilledPrice);
        if (securityName != null) order.setSecurityName(securityName);
        LocalDateTime now = LocalDateTime.now();
        order.setUpdatedAt(now);
        orderMapper.updateById(order);
        if (statusChanged || filledChanged || averageChanged) {
            appendStatusHistory(order, previousStatus, source, brokerMessage, now);
        }
        return true;
    }

    private void appendStatusHistory(TradeOrder order, String previousStatus, String source,
                                     String brokerMessage, LocalDateTime observedAt) {
        TradeOrderStatusHistory history = new TradeOrderStatusHistory();
        history.setOrderId(order.getId());
        history.setPreviousStatus(previousStatus);
        history.setCurrentStatus(order.getStatus());
        history.setFilledQuantity(order.getFilledQuantity() == null ? BigDecimal.ZERO : order.getFilledQuantity());
        history.setAverageFilledPrice(order.getAverageFilledPrice());
        history.setSource(source);
        history.setBrokerMessage(truncate(brokerMessage, 500));
        history.setObservedAt(observedAt);
        history.setCreatedAt(observedAt);
        historyMapper.insert(history);
    }

    private void appendAudit(TradeOrder order, String action, String idempotencyKey, String source,
                             Map<String, Object> detail, LocalDateTime now) {
        TradeOrderAudit audit = new TradeOrderAudit();
        audit.setOrderId(order.getId());
        audit.setUserId(order.getUserId());
        audit.setAction(action);
        audit.setIdempotencyKey(idempotencyKey);
        audit.setSource(source);
        audit.setDetailJson(json(detail));
        audit.setCreatedAt(now);
        auditMapper.insert(audit);
    }

    private TradeOrder require(Long orderId) {
        TradeOrder order = orderMapper.selectById(orderId);
        if (order == null) throw new IllegalStateException("订单状态写入时订单不存在: " + orderId);
        return order;
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("订单审计JSON序列化失败", ex);
        }
    }

    private String status(Object value, String fallback) {
        String result = nonBlank(value, fallback);
        return result == null ? fallback : result.trim().toUpperCase();
    }

    private String nonBlank(Object value, String fallback) {
        if (value == null || String.valueOf(value).isBlank()) return fallback;
        return String.valueOf(value).trim();
    }

    private BigDecimal decimal(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean sameDecimal(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private String decimalText(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private String truncate(String value, int max) {
        if (value == null || value.isBlank()) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record PendingOrder(TradeOrder order, boolean newlyCreated) {
    }

    public record CancelRequest(TradeOrder order, boolean needsDispatch, boolean duplicate) {
    }
}
