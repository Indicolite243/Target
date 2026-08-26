package com.stockmanager.trade.order.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmanager.account.entity.Account;
import com.stockmanager.account.mapper.AccountMapper;
import com.stockmanager.common.exception.BusinessException;
import com.stockmanager.integration.quant.QuantClient;
import com.stockmanager.trade.order.dto.SubmitOrderRequest;
import com.stockmanager.trade.order.entity.TradeOrder;
import com.stockmanager.trade.order.entity.TradeOrderAudit;
import com.stockmanager.trade.order.entity.TradeOrderStatusHistory;
import com.stockmanager.trade.order.mapper.TradeOrderAuditMapper;
import com.stockmanager.trade.order.mapper.TradeOrderMapper;
import com.stockmanager.trade.order.mapper.TradeOrderStatusHistoryMapper;
import com.stockmanager.trade.order.service.OrderStatePersistenceService;
import com.stockmanager.trade.order.service.OrderStatusPolicy;
import com.stockmanager.trade.order.service.OrderService;
import com.stockmanager.trade.order.vo.OrderView;
import com.stockmanager.trade.order.vo.OrderPageView;
import com.stockmanager.trade.order.vo.OrderTimelineView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class OrderServiceImpl implements OrderService {
    private final TradeOrderMapper orderMapper;
    private final TradeOrderStatusHistoryMapper historyMapper;
    private final TradeOrderAuditMapper auditMapper;
    private final AccountMapper accountMapper;
    private final QuantClient quantClient;
    private final OrderStatePersistenceService statePersistenceService;
    private final ObjectMapper objectMapper;

    public OrderServiceImpl(TradeOrderMapper orderMapper, TradeOrderStatusHistoryMapper historyMapper,
                            TradeOrderAuditMapper auditMapper, AccountMapper accountMapper, QuantClient quantClient,
                            OrderStatePersistenceService statePersistenceService, ObjectMapper objectMapper) {
        this.orderMapper = orderMapper;
        this.historyMapper = historyMapper;
        this.auditMapper = auditMapper;
        this.accountMapper = accountMapper;
        this.quantClient = quantClient;
        this.statePersistenceService = statePersistenceService;
        this.objectMapper = objectMapper;
    }

    /** The QMT network call deliberately sits outside every MySQL transaction. */
    @Override
    public OrderView submit(Long userId, String idempotencyKey, SubmitOrderRequest request, String traceId) {
        TradeOrder existing = statePersistenceService.findByIdempotency(userId, idempotencyKey);
        if (existing != null) return toView(existing, "返回首次幂等处理结果");

        Account account = requireAccount(userId, Long.valueOf(request.accountId()));
        validateRequest(account, request);
        TradeOrder draft = newOrder(userId, idempotencyKey, account, request);
        OrderStatePersistenceService.PendingOrder pending = statePersistenceService.createPending(draft, traceId);
        if (!pending.newlyCreated()) return toView(pending.order(), "返回首次幂等处理结果");

        try {
            TradeOrder accepted = statePersistenceService.applySubmitAccepted(pending.order().getId(),
                    quantClient.submitOrder(submitPayload(pending.order(), account), traceId), traceId);
            return toView(accepted, "UNKNOWN".equals(accepted.getStatus()) ? "委托状态确认中" : "委托已受理");
        } catch (BusinessException ex) {
            TradeOrder latest = ex.getStatus().is4xxClientError()
                    ? statePersistenceService.markSubmitRejected(pending.order().getId(), traceId, ex.getMessage())
                    : statePersistenceService.markSubmitUnknown(pending.order().getId(), traceId, ex.getMessage());
            return toView(latest, "REJECTED".equals(latest.getStatus()) ? "委托被QMT拒绝" : "委托状态确认中");
        }
    }

    /** List requests are MySQL-only, so UI refreshes never wait for QMT. */
    @Override
    public OrderPageView list(Long userId, Long accountId, long page, long pageSize,
                              LocalDateTime start, LocalDateTime endExclusive) {
        long safePage = Math.max(1, page);
        long safePageSize = Math.clamp(pageSize, 1, 100);
        var query = Wrappers.<TradeOrder>lambdaQuery().eq(TradeOrder::getUserId, userId);
        if (accountId != null) query.eq(TradeOrder::getAccountId, accountId);
        query.isNull(TradeOrder::getDeletedAt);
        if (start != null) query.ge(TradeOrder::getCreatedAt, start);
        if (endExclusive != null) query.lt(TradeOrder::getCreatedAt, endExclusive);
        query.isNotNull(TradeOrder::getSymbol).ne(TradeOrder::getSymbol, "")
                .gt(TradeOrder::getQuantity, BigDecimal.ZERO).orderByDesc(TradeOrder::getCreatedAt);
        Page<TradeOrder> dbPage = orderMapper.selectPage(new Page<>(safePage, safePageSize), query);
        List<OrderView> items = dbPage.getRecords().stream().map(order -> toView(order, null)).toList();
        return new OrderPageView(items, dbPage.getCurrent(), dbPage.getSize(), dbPage.getTotal(),
                dbPage.getPages(), dbPage.hasNext());
    }

    @Override
    @Transactional
    public void deleteHistory(Long userId, Long orderId, String traceId) {
        TradeOrder order = requireOrder(userId, orderId);
        if (order.getDeletedAt() != null) return;
        if (OrderStatusPolicy.isTrackable(order.getStatus())) {
            throw new BusinessException(409303, "进行中的委托不能删除，请先撤单或等待终态", HttpStatus.CONFLICT);
        }
        markHistoryDeleted(order, userId, traceId);
    }

    @Override
    @Transactional
    public int deleteFilteredHistory(Long userId, LocalDateTime start, LocalDateTime endExclusive, String traceId) {
        var query = Wrappers.<TradeOrder>lambdaQuery().eq(TradeOrder::getUserId, userId)
                .isNull(TradeOrder::getDeletedAt)
                .isNotNull(TradeOrder::getSymbol).ne(TradeOrder::getSymbol, "")
                .gt(TradeOrder::getQuantity, BigDecimal.ZERO)
                .notIn(TradeOrder::getStatus, OrderStatusPolicy.trackableStoredStates());
        if (start != null) query.ge(TradeOrder::getCreatedAt, start);
        if (endExclusive != null) query.lt(TradeOrder::getCreatedAt, endExclusive);
        List<TradeOrder> orders = orderMapper.selectList(query);
        orders.forEach(order -> markHistoryDeleted(order, userId, traceId));
        return orders.size();
    }

    private void markHistoryDeleted(TradeOrder order, Long userId, String traceId) {
        LocalDateTime now = LocalDateTime.now();
        order.setDeletedAt(now);
        order.setUpdatedAt(now);
        orderMapper.updateById(order);
        TradeOrderAudit audit = new TradeOrderAudit();
        audit.setOrderId(order.getId());
        audit.setUserId(userId);
        audit.setAction("DELETE_HISTORY");
        audit.setIdempotencyKey(null);
        audit.setSource("WEB");
        audit.setDetailJson(objectMapper.createObjectNode().put("traceId", traceId == null ? "" : traceId).toString());
        audit.setCreatedAt(now);
        auditMapper.insert(audit);
    }

    /** The QMT network call deliberately sits outside every MySQL transaction. */
    @Override
    public OrderView cancel(Long userId, Long orderId, String idempotencyKey, String traceId) {
        requireOrder(userId, orderId);
        OrderStatePersistenceService.CancelRequest request = statePersistenceService.requestCancel(orderId,
                idempotencyKey, traceId);
        if (!request.needsDispatch()) {
            if (request.duplicate()) return toView(request.order(), "返回首次幂等处理结果");
            throw new BusinessException(409302, "当前订单状态不允许撤单", HttpStatus.CONFLICT);
        }

        try {
            Account account = requireAccount(userId, request.order().getAccountId());
            TradeOrder accepted = statePersistenceService.applyCancelAccepted(orderId,
                    quantClient.cancelOrder(cancelPayload(request.order(), account, idempotencyKey), traceId), traceId);
            return toView(accepted, "撤单请求已受理");
        } catch (BusinessException ex) {
            TradeOrder latest = statePersistenceService.markCancelUnconfirmed(orderId, traceId, ex.getMessage());
            return toView(latest, "撤单状态确认中");
        }
    }

    /**
     * One scheduled QMT read serves all unfinished QMT orders. It makes no call
     * when there is nothing to reconcile and writes only actual state changes.
     */
    @Override
    public int refreshOpenOrderStatuses() {
        Set<Long> qmtAccountIds = accountMapper.selectList(Wrappers.<Account>lambdaQuery()
                        .eq(Account::getBroker, "GUOJIN_QMT")
                        .ne(Account::getStatus, "DISABLED"))
                .stream().map(Account::getId).collect(Collectors.toSet());
        if (qmtAccountIds.isEmpty()) return 0;
        List<TradeOrder> orders = orderMapper.selectList(Wrappers.<TradeOrder>lambdaQuery()
                .in(TradeOrder::getAccountId, qmtAccountIds)
                .in(TradeOrder::getStatus, OrderStatusPolicy.trackableStoredStates())
                .isNotNull(TradeOrder::getExternalOrderNo));
        if (orders.isEmpty()) return 0;

        try {
            Object raw = quantClient.queryOrders("order-status-poller").get("orders");
            if (!(raw instanceof List<?> rows)) return 0;
            Map<String, Map<?, ?>> qmtByOrderNo = new LinkedHashMap<>();
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map && map.get("externalOrderNo") != null) {
                    qmtByOrderNo.put(String.valueOf(map.get("externalOrderNo")), map);
                }
            }
            int changed = 0;
            for (TradeOrder order : orders) {
                Map<?, ?> qmt = qmtByOrderNo.get(order.getExternalOrderNo());
                if (qmt != null && statePersistenceService.applyBrokerStatus(order.getId(), qmt, "order-status-poller")) {
                    changed++;
                }
            }
            return changed;
        } catch (BusinessException ignored) {
            // Keep the last confirmed MySQL state while MiniQMT is unavailable.
            return 0;
        }
    }

    @Override
    public OrderTimelineView timeline(Long userId, Long orderId) {
        requireOrder(userId, orderId);
        List<OrderTimelineView.StatusItem> statusHistory = historyMapper.selectList(
                        Wrappers.<TradeOrderStatusHistory>lambdaQuery().eq(TradeOrderStatusHistory::getOrderId, orderId)
                                .orderByAsc(TradeOrderStatusHistory::getObservedAt))
                .stream().map(item -> new OrderTimelineView.StatusItem(item.getPreviousStatus(), item.getCurrentStatus(),
                        decimal(item.getFilledQuantity()), decimal(item.getAverageFilledPrice()), item.getSource(),
                        item.getBrokerMessage(), item.getObservedAt())).toList();
        List<OrderTimelineView.AuditItem> audit = auditMapper.selectList(
                        Wrappers.<TradeOrderAudit>lambdaQuery().eq(TradeOrderAudit::getOrderId, orderId)
                                .orderByAsc(TradeOrderAudit::getCreatedAt))
                .stream().map(item -> new OrderTimelineView.AuditItem(item.getAction(), item.getSource(),
                        auditDetail(item.getDetailJson()), item.getCreatedAt())).toList();
        return new OrderTimelineView(String.valueOf(orderId), statusHistory, audit);
    }

    private TradeOrder newOrder(Long userId, String idempotencyKey, Account account, SubmitOrderRequest request) {
        TradeOrder order = new TradeOrder();
        order.setAccountId(account.getId());
        order.setUserId(userId);
        order.setIdempotencyKey(idempotencyKey);
        order.setSymbol(request.symbol());
        order.setSecurityName(request.securityName() == null || request.securityName().isBlank()
                ? request.symbol() : request.securityName());
        order.setSide(request.side());
        order.setOrderType(request.orderType());
        order.setQuantity(new BigDecimal(request.quantity()));
        order.setPrice(request.price() == null || request.price().isBlank() ? null : new BigDecimal(request.price()));
        order.setEnvironment(request.environment());
        order.setRemark(request.remark());
        return order;
    }

    private void validateRequest(Account account, SubmitOrderRequest request) {
        if (!account.getEnvironment().equals(request.environment())) {
            throw new BusinessException(422301, "委托环境与账户环境不一致", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        BigDecimal price = request.price() == null || request.price().isBlank() ? null : new BigDecimal(request.price());
        if ("LIMIT".equals(request.orderType()) && (price == null || price.signum() <= 0)) {
            throw new BusinessException(422301, "限价单价格必须大于0", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private Map<String, Object> submitPayload(TradeOrder order, Account account) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", String.valueOf(order.getId()));
        payload.put("clientOrderNo", order.getClientOrderNo());
        payload.put("externalAccountId", account.getAccountNo());
        payload.put("symbol", order.getSymbol());
        payload.put("side", order.getSide());
        payload.put("orderType", order.getOrderType());
        payload.put("quantity", order.getQuantity().toPlainString());
        payload.put("price", order.getPrice() == null ? null : order.getPrice().toPlainString());
        payload.put("environment", order.getEnvironment());
        return payload;
    }

    private Map<String, Object> cancelPayload(TradeOrder order, Account account, String idempotencyKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", String.valueOf(order.getId()));
        payload.put("clientOrderNo", order.getClientOrderNo());
        payload.put("externalOrderNo", order.getExternalOrderNo() == null ? "" : order.getExternalOrderNo());
        payload.put("externalAccountId", account.getAccountNo());
        payload.put("environment", order.getEnvironment());
        payload.put("idempotencyKey", idempotencyKey);
        return payload;
    }

    private TradeOrder requireOrder(Long userId, Long orderId) {
        TradeOrder order = orderMapper.selectOne(Wrappers.<TradeOrder>lambdaQuery()
                .eq(TradeOrder::getId, orderId).eq(TradeOrder::getUserId, userId));
        if (order == null) throw new BusinessException(404301, "订单不存在", HttpStatus.NOT_FOUND);
        return order;
    }

    private Account requireAccount(Long userId, Long accountId) {
        Account account = accountMapper.selectOne(Wrappers.<Account>lambdaQuery()
                .eq(Account::getId, accountId).eq(Account::getUserId, userId));
        if (account == null) throw new BusinessException(404101, "账户不存在", HttpStatus.NOT_FOUND);
        return account;
    }

    private OrderView toView(TradeOrder order, String message) {
        return new OrderView(String.valueOf(order.getId()), order.getClientOrderNo(), order.getExternalOrderNo(),
                String.valueOf(order.getAccountId()), order.getSymbol(), order.getSecurityName(), order.getSide(),
                order.getOrderType(), decimal(order.getQuantity()), decimal(order.getPrice()),
                decimal(order.getFilledQuantity()), decimal(order.getAverageFilledPrice()), order.getStatus(),
                order.getEnvironment(), order.getCreatedAt(), order.getUpdatedAt(), message);
    }

    private String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private Map<String, Object> auditDetail(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }
}
