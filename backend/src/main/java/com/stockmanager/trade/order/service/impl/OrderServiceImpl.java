package com.stockmanager.trade.order.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stockmanager.account.entity.Account;
import com.stockmanager.account.mapper.AccountMapper;
import com.stockmanager.common.exception.BusinessException;
import com.stockmanager.integration.quant.QuantClient;
import com.stockmanager.trade.order.dto.SubmitOrderRequest;
import com.stockmanager.trade.order.entity.TradeOrder;
import com.stockmanager.trade.order.mapper.TradeOrderMapper;
import com.stockmanager.trade.order.service.OrderService;
import com.stockmanager.trade.order.vo.OrderView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OrderServiceImpl implements OrderService {
    private final TradeOrderMapper orderMapper;
    private final AccountMapper accountMapper;
    private final QuantClient quantClient;

    public OrderServiceImpl(TradeOrderMapper orderMapper, AccountMapper accountMapper, QuantClient quantClient) {
        this.orderMapper = orderMapper;
        this.accountMapper = accountMapper;
        this.quantClient = quantClient;
    }

    @Override
    @Transactional
    public OrderView submit(Long userId, String idempotencyKey, SubmitOrderRequest request, String traceId) {
        TradeOrder existing = orderMapper.selectOne(Wrappers.<TradeOrder>lambdaQuery()
                .eq(TradeOrder::getUserId, userId).eq(TradeOrder::getIdempotencyKey, idempotencyKey));
        if (existing != null) return toView(existing, "返回首次幂等处理结果");

        Account account = requireAccount(userId, Long.valueOf(request.accountId()));
        if (!account.getEnvironment().equals(request.environment())) {
            throw new BusinessException(422301, "委托环境与账户环境不一致", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        BigDecimal price = request.price() == null || request.price().isBlank() ? null : new BigDecimal(request.price());
        if ("LIMIT".equals(request.orderType()) && (price == null || price.signum() <= 0)) {
            throw new BusinessException(422301, "限价单价格必须大于0", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        TradeOrder order = new TradeOrder();
        order.setAccountId(account.getId());
        order.setUserId(userId);
        order.setIdempotencyKey(idempotencyKey);
        order.setClientOrderNo("ORD-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")));
        order.setSymbol(request.symbol());
        order.setSecurityName(request.securityName() == null || request.securityName().isBlank()
                ? request.symbol() : request.securityName());
        order.setSide(request.side());
        order.setOrderType(request.orderType());
        order.setQuantity(new BigDecimal(request.quantity()));
        order.setPrice(price);
        order.setFilledQuantity(BigDecimal.ZERO);
        order.setStatus("PENDING_SUBMIT");
        order.setEnvironment(request.environment());
        order.setRemark(request.remark());
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        orderMapper.insert(order);

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
        Map<String, Object> result = quantClient.submitOrder(payload, traceId);
        order.setExternalOrderNo(string(result.get("externalOrderNo")));
        order.setStatus(string(result.getOrDefault("status", "SUBMITTED")));
        order.setUpdatedAt(LocalDateTime.now());
        orderMapper.updateById(order);
        return toView(order, "UNKNOWN".equals(order.getStatus()) ? "委托状态确认中" : "委托已受理");
    }

    @Override
    public List<OrderView> list(Long userId, Long accountId) {
        var query = Wrappers.<TradeOrder>lambdaQuery().eq(TradeOrder::getUserId, userId);
        if (accountId != null) query.eq(TradeOrder::getAccountId, accountId);
        query.orderByDesc(TradeOrder::getCreatedAt);
        List<TradeOrder> orders = orderMapper.selectList(query).stream()
                .filter(order -> order.getSymbol() != null && !order.getSymbol().isBlank()
                        && order.getQuantity() != null && order.getQuantity().signum() > 0)
                .toList();
        refreshQmtStatuses(orders, traceIdForList());
        return orders.stream().map(order -> toView(order, null)).toList();
    }

    private void refreshQmtStatuses(List<TradeOrder> orders, String traceId) {
        if (orders.isEmpty()) return;
        try {
            Object raw = quantClient.queryOrders(traceId).get("orders");
            if (!(raw instanceof List<?> rows)) return;
            Map<String, Map<?, ?>> qmtByOrderNo = new LinkedHashMap<>();
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map && map.get("externalOrderNo") != null) {
                    qmtByOrderNo.put(String.valueOf(map.get("externalOrderNo")), map);
                }
            }
            for (TradeOrder order : orders) {
                Map<?, ?> qmt = qmtByOrderNo.get(order.getExternalOrderNo());
                if (qmt == null) continue;
                order.setStatus(string(qmt.get("status")));
                order.setFilledQuantity(decimalValue(qmt.get("tradedQuantity")));
                order.setAverageFilledPrice(decimalValue(qmt.get("tradedPrice")));
                String name = string(qmt.get("securityName"));
                if (name != null && !name.isBlank() && !name.equals(order.getSymbol())) order.setSecurityName(name);
                order.setUpdatedAt(LocalDateTime.now());
                orderMapper.updateById(order);
            }
        } catch (BusinessException ignored) {
            // Keep the last known local state when MiniQMT is temporarily unavailable.
        }
    }

    private String traceIdForList() { return "order-list-sync"; }

    @Override
    @Transactional
    public OrderView cancel(Long userId, Long orderId, String idempotencyKey, String traceId) {
        TradeOrder order = orderMapper.selectOne(Wrappers.<TradeOrder>lambdaQuery()
                .eq(TradeOrder::getId, orderId).eq(TradeOrder::getUserId, userId));
        if (order == null) throw new BusinessException(404301, "订单不存在", HttpStatus.NOT_FOUND);
        if (!List.of("SUBMITTED", "PARTIALLY_FILLED", "CANCEL_PENDING", "UNKNOWN").contains(order.getStatus())) {
            throw new BusinessException(409302, "当前订单状态不允许撤单", HttpStatus.CONFLICT);
        }
        order.setStatus("CANCEL_PENDING");
        order.setUpdatedAt(LocalDateTime.now());
        orderMapper.updateById(order);
        try {
            Account account = requireAccount(userId, order.getAccountId());
            Map<String, Object> result = quantClient.cancelOrder(Map.of(
                    "orderId", String.valueOf(order.getId()),
                    "clientOrderNo", order.getClientOrderNo(),
                    "externalOrderNo", order.getExternalOrderNo() == null ? "" : order.getExternalOrderNo(),
                    "externalAccountId", account.getAccountNo(),
                    "environment", order.getEnvironment(),
                    "idempotencyKey", idempotencyKey
            ), traceId);
            order.setStatus(string(result.getOrDefault("status", "CANCELED")));
        } catch (BusinessException ignored) {
            order.setStatus("CANCEL_PENDING");
        }
        order.setUpdatedAt(LocalDateTime.now());
        orderMapper.updateById(order);
        return toView(order, "撤单请求已受理");
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

    private String decimal(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
    private BigDecimal decimalValue(Object value) {
        try { return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value)); }
        catch (NumberFormatException ex) { return BigDecimal.ZERO; }
    }
    private String string(Object value) { return value == null ? null : String.valueOf(value); }
}
