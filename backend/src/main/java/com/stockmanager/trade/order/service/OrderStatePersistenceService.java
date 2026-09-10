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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 订单状态的唯一持久化边界。
 *
 * <p>本类只包含短 MySQL 事务：订单状态、状态历史和审计记录在同一个事务中写入；
 * QMT 网络调用必须由上层在进入本类之前或离开本类之后完成，绝不能在数据库事务里等待券商响应。</p>
 *
 * <p>把这些方法单独放在 Spring Service 中还有一个目的：调用方通过 Bean 代理进入
 * {@code @Transactional}，不会因同类内部调用绕过事务代理。</p>
 *
 * <pre>
 * API --短事务--> PENDING_SUBMIT --事务外 QMT--> SUBMITTED/REJECTED
 *                                      \--超时--> UNKNOWN --后台对账--> 已成/已撤/继续已报
 * 撤单请求 --短事务--> CANCEL_PENDING --事务外 QMT--> 后台对账确认最终状态
 * </pre>
 */
@Service
public class OrderStatePersistenceService {
    /** clientOrderNo的时间部分格式，精确到毫秒，便于人工按时间定位订单。 */
    private static final DateTimeFormatter ORDER_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    /** 订单主表Mapper。 */
    private final TradeOrderMapper orderMapper;
    /** 状态历史Mapper。 */
    private final TradeOrderStatusHistoryMapper historyMapper;
    /** 操作审计Mapper。 */
    private final TradeOrderAuditMapper auditMapper;
    /** 审计详情JSON编解码器。 */
    private final ObjectMapper objectMapper;

    /** 注入订单、状态历史、审计 Mapper 和 JSON 编解码器。 */
    public OrderStatePersistenceService(TradeOrderMapper orderMapper, TradeOrderStatusHistoryMapper historyMapper,
                                        TradeOrderAuditMapper auditMapper, ObjectMapper objectMapper) {
        this.orderMapper = orderMapper;
        this.historyMapper = historyMapper;
        this.auditMapper = auditMapper;
        this.objectMapper = objectMapper;
    }

    /** 创建 PENDING_SUBMIT 订单、初始状态历史和提交审计；唯一键冲突时复用原订单。 */
    @Transactional
    public PendingOrder createPending(TradeOrder order, String traceId) {
        // 先查是常见路径优化；并发场景仍必须依赖数据库的 (user_id, idempotency_key) 唯一索引。
        TradeOrder existing = findByIdempotency(order.getUserId(), order.getIdempotencyKey());
        // 已有订单说明本次幂等请求已经进入过系统，直接返回且不产生任何新写入。
        if (existing != null) return new PendingOrder(existing, false);

        // 所有本地创建时间使用同一个now，保证主表和初始历史的时间顺序一致。
        LocalDateTime now = LocalDateTime.now();
        // clientOrderNo是系统可读订单号；UUID后缀避免同毫秒内多个请求碰撞。
        order.setClientOrderNo("ORD-" + now.format(ORDER_TIME) + "-" + UUID.randomUUID().toString().substring(0, 8));
        // 新订单尚未成交，累计成交数量从0开始而不是null。
        order.setFilledQuantity(BigDecimal.ZERO);
        // 本地意图状态先写PENDING_SUBMIT，等待事务外QMT调用结果。
        order.setStatus("PENDING_SUBMIT");
        // 保存创建时间和最近更新时间。
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        try {
            // insert同时触发MyBatis-Plus主键生成，并受数据库唯一幂等索引保护。
            orderMapper.insert(order);
        } catch (DuplicateKeyException ex) {
            // 两个并发请求可能同时通过上面的查询，只有一个能插入成功；失败者读取胜出的订单即可。
            TradeOrder raced = findByIdempotency(order.getUserId(), order.getIdempotencyKey());
            // 如果能读到并发请求已经插入的订单，则复用它；这次调用不再重复写历史/审计。
            if (raced != null) return new PendingOrder(raced, false);
            // 唯一键冲突但查不到胜者通常表示数据库异常，交给上层处理而不是伪造成功。
            throw ex;
        }
        // 初始状态、状态历史和审计必须在同一事务里完成，否则会出现“有订单但无起始轨迹”。
        //调用fast api写入
        appendStatusHistory(order, null, "API", "等待向QMT提交", now);
        appendAudit(order, "SUBMIT_REQUESTED", order.getIdempotencyKey(), "API", Map.of(
                "traceId", safe(traceId), "clientOrderNo", order.getClientOrderNo(), "status", order.getStatus()), now);
        return new PendingOrder(order, true);
    }

    /** 根据 QMT 返回更新外部订单号和已受理状态。 */
    @Transactional
    public TradeOrder applySubmitAccepted(Long orderId, Map<String, Object> result, String traceId) {
        // QMT 的原始状态先统一映射为系统状态，页面和后台不需要理解各版本 SDK 的状态枚举。
        // 重新按主键读取，避免使用事务外调用前的旧实体覆盖并发状态。
        TradeOrder order = require(orderId);
        // 读取QMT返回状态；缺失时保守按SUBMITTED处理。
        String rawStatus = status(result.get("status"), "SUBMITTED");
        // 归一化为应用状态，保证主表状态可以被统一状态机识别。
        String status = OrderStatusPolicy.canonicalizeBrokerStatus(rawStatus, "SUBMITTED");
        // 外部订单号是后续查询/撤单关键；空返回时保留已有值。
        String externalOrderNo = nonBlank(result.get("externalOrderNo"), order.getExternalOrderNo());
        // 状态消息只用于展示和审计，空消息不写入。
        String message = nonBlank(result.get("statusMessage"), null);
        // 在同一短事务内更新主表，并按变化追加状态历史。
        updateOrder(order, status, externalOrderNo, null, null, null, "QMT_SUBMIT", message);
        // 追加“外部已受理”审计，保留原始状态方便排查QMT枚举差异。
        appendAudit(order, "SUBMIT_ACCEPTED", null, "QMT_SUBMIT", Map.of(
                "traceId", safe(traceId), "externalOrderNo", safe(externalOrderNo),
                "rawBrokerStatus", rawStatus, "status", status), LocalDateTime.now());
        return order;
    }

    /** 将结果不确定的提交标记为 UNKNOWN，等待后台对账。 */
    @Transactional
    public TradeOrder markSubmitUnknown(Long orderId, String traceId, String reason) {
        // 超时不等于拒绝：QMT 可能已经受理，所以 UNKNOWN 只能等待对账，不能触发二次下单。
        TradeOrder order = require(orderId);
        // UNKNOWN不清空可能已经存在的外部订单号，也不触发第二次QMT下单。
        updateOrder(order, "UNKNOWN", null, null, null, null, "QMT_SUBMIT", reason);
        // 审计记录网络/协议异常原因，供人工和后台对账定位。
        appendAudit(order, "SUBMIT_UNKNOWN", null, "QMT_SUBMIT", Map.of(
                "traceId", safe(traceId), "reason", safe(reason)), LocalDateTime.now());
        return order;
    }

    /** 将 QMT 明确拒绝的提交标记为 REJECTED。 */
    @Transactional
    public TradeOrder markSubmitRejected(Long orderId, String traceId, String reason) {
        // 按主键读取最新实体，防止覆盖后台已经写入的字段。
        TradeOrder order = require(orderId);
        // 明确拒绝进入终态REJECTED，后续不再作为在途订单轮询。
        updateOrder(order, "REJECTED", null, null, null, null, "QMT_SUBMIT", reason);
        // 记录拒绝原因和链路号，审计详情不包含敏感凭据。
        appendAudit(order, "SUBMIT_REJECTED", null, "QMT_SUBMIT", Map.of(
                "traceId", safe(traceId), "reason", safe(reason)), LocalDateTime.now());
        return order;
    }

    /** 原子申请撤单并返回是否需要真正调用 QMT。 */
    @Transactional
    public CancelRequest requestCancel(Long orderId, String idempotencyKey, String traceId) {
        // 重新读取订单，撤单判断必须基于数据库当前状态而不是前端缓存。
        TradeOrder order = require(orderId);
        // 撤单也需要幂等键，防止用户连续点击或网络重试生成多条撤单意图。
        TradeOrderAudit duplicate = auditMapper.selectOne(Wrappers.<TradeOrderAudit>lambdaQuery()
                .eq(TradeOrderAudit::getOrderId, orderId)
                .eq(TradeOrderAudit::getAction, "CANCEL_REQUESTED")
                .eq(TradeOrderAudit::getIdempotencyKey, idempotencyKey));
        // 同一订单+同一撤单幂等键已经写过审计，返回duplicate让上层不再调用QMT。
        if (duplicate != null) return new CancelRequest(order, false, true);
        // 终态、待提交和已在撤单中的订单不能再次发起撤单。
        if (!OrderStatusPolicy.canCancel(order.getStatus())) return new CancelRequest(order, false, false);

        // 这里只记录“准备撤单”，不代表券商最终已撤；最终结果由 QMT 对账决定。
        updateOrder(order, "CANCEL_PENDING", null, null, null, null, "API", "等待QMT撤单确认");
        // 审计写入幂等键，使下一次相同请求可以被识别为重复操作。
        appendAudit(order, "CANCEL_REQUESTED", idempotencyKey, "API", Map.of(
                "traceId", safe(traceId), "status", order.getStatus()), LocalDateTime.now());
        return new CancelRequest(order, true, false);
    }

    /** 记录 QMT 已受理撤单请求；这不代表最终已经撤成。 */
    @Transactional
    public TradeOrder applyCancelAccepted(Long orderId, Map<String, Object> result, String traceId) {
        // QMT返回后重新读取主表，在最新实体上应用撤单响应。
        TradeOrder order = require(orderId);
        // 撤单接口缺少状态时按CANCELED作为兼容默认值，但最终仍由轮询确认。
        String rawStatus = status(result.get("status"), "CANCELED");
        // 统一QMT原始状态名称，禁止旧枚举直接进入主表。
        String status = OrderStatusPolicy.canonicalizeBrokerStatus(rawStatus, "CANCELED");
        // 提取可选的券商提示，用于状态历史和审计。
        String message = nonBlank(result.get("statusMessage"), null);
        // 写入QMT撤单调用结果；这表示请求受理，不等价于最终撤成。
        updateOrder(order, status, null, null, null, null, "QMT_CANCEL", message);
        // 记录撤单调用成功事实。
        appendAudit(order, "CANCEL_ACCEPTED", null, "QMT_CANCEL", Map.of(
                "traceId", safe(traceId), "rawBrokerStatus", rawStatus, "status", status), LocalDateTime.now());
        return order;
    }

    /** 撤单调用结果不确定时保留可对账状态和原因。 */
    @Transactional
    public TradeOrder markCancelUnconfirmed(Long orderId, String traceId, String reason) {
        // 保持requestCancel写入的CANCEL_PENDING状态，只增加“未确认”审计。
        TradeOrder order = require(orderId);
        // 不能因一次网络异常把可能已成交的订单标记为CANCELED。
        appendAudit(order, "CANCEL_UNCONFIRMED", null, "QMT_CANCEL", Map.of(
                "traceId", safe(traceId), "reason", safe(reason)), LocalDateTime.now());
        return order;
    }

    /**
     * 应用一次券商订单状态快照；无实际变化时不写数据库。
     *
     * <p>只有状态、成交量、成交均价或证券名称发生变化时才更新主表并追加历史，
     * 这样三秒轮询不会在状态不变时持续制造数据库写入。撤单确认中的订单会忽略券商短暂返回的
     * 旧 REPORTED 状态，避免状态回退后再次开放撤单。</p>
     */
    @Transactional
    public boolean applyBrokerStatus(Long orderId, Map<?, ?> row, String traceId) {
        // 每次轮询都按主键读取最新订单，避免旧快照覆盖并发产生的终态。
        TradeOrder order = orderMapper.selectById(orderId);
        // 订单不存在或已经终态时无需再写库，保证终态不可被旧回报回退。
        if (order == null || OrderStatusPolicy.isTerminal(order.getStatus())) return false;
        // 读取QMT原始状态；缺少状态时沿用当前状态，避免无意义变更。
        String rawStatus = nonBlank(row.get("status"), order.getStatus());
        // 将券商观测与本地状态合并，特别保护CANCEL_PENDING不被旧状态回退。
        String status = OrderStatusPolicy.resolveBrokerObservation(order.getStatus(), rawStatus);
        // QMT字段可能是String、Integer或null，统一解析为BigDecimal。
        BigDecimal filled = decimal(row.get("tradedQuantity"));
        // 读取累计成交均价；没有成交时保持null/旧值。
        BigDecimal average = decimal(row.get("tradedPrice"));
        // 即使QMT状态仍是SUBMITTED，只要累计成交量覆盖委托量就确定为FILLED。
        if (isFullyFilled(order, filled)) status = "FILLED";
        // 状态回报可能补充证券名称；缺失时保留本地名称。
        String securityName = nonBlank(row.get("securityName"), order.getSecurityName());
        // 状态消息只保留可展示文本，最终由updateOrder写入历史并截断。
        String message = nonBlank(row.get("statusMessage"), null);
        // updateOrder会先比较字段，只有真实变化才更新主表并追加状态历史。
        boolean changed = updateOrder(order, status, null, filled, average, securityName,
                "QMT_STATUS_POLL", message);
        if (changed) {
            // 状态变化额外记录审计，保存原始状态和累计成交量用于事后核对。
            appendAudit(order, "STATUS_CHANGED", null, "QMT_STATUS_POLL", Map.of(
                    "traceId", safe(traceId), "rawBrokerStatus", rawStatus, "status", order.getStatus(),
                    "filledQuantity", safe(decimalText(order.getFilledQuantity()))), LocalDateTime.now());
        }
        return changed;
    }

    /**
     * 收敛跨日后已不在QMT当日列表中的撤单请求。
     *
     * <p>该方法再次读取数据库并校验日期和状态，防止轮询查询与其他状态写入并发时覆盖终态。
     * 全部成交的订单记为 FILLED，其余记为 CANCELED；状态历史和审计记录保留自动收敛原因。</p>
     */
    @Transactional
    public boolean reconcileMissingPriorDayCancellation(Long orderId, LocalDate currentDate, String traceId) {
        // 再次按主键读取并校验，防止轮询列表生成后订单已进入终态。
        TradeOrder order = orderMapper.selectById(orderId);
        // 只有策略允许的“昨日以前撤单中且今日QMT列表缺失”才可以自动收敛。
        if (order == null || !OrderStatusPolicy.shouldReconcileMissingPriorDayCancellation(
                order.getStatus(), order.getCreatedAt(), currentDate)) return false;

        // 若累计成交覆盖委托量，跨日缺失应收敛为FILLED；否则才视为CANCELED。
        String targetStatus = isFullyFilled(order, order.getFilledQuantity()) ? "FILLED" : "CANCELED";
        // 明确写入自动收敛原因，避免人工误认为QMT返回了明确撤单回报。
        String message = "跨日后未出现在QMT当日委托列表，撤单状态自动收敛";
        // 更新主表并在状态发生变化时追加历史。
        boolean changed = updateOrder(order, targetStatus, null, null, null, null,
                "QMT_CROSS_DAY_RECONCILE", message);
        if (changed) {
            // 审计保留自动判定依据，便于解释为什么没有直接拿到QMT订单行。
            appendAudit(order, "CROSS_DAY_RECONCILED", null, "QMT_STATUS_POLL", Map.of(
                    "traceId", safe(traceId), "status", targetStatus,
                    "reason", "missing_from_qmt_current_day_orders"), LocalDateTime.now());
        }
        return changed;
    }

    /** 按用户和幂等键查询首次提交结果。 */
//    SELECT *
//    FROM trade_order
//    WHERE user_id = ?
//    AND idempotency_key = ?;
    public TradeOrder findByIdempotency(Long userId, String idempotencyKey) {
        // 查询条件必须同时包含userId，避免不同用户恰好使用相同幂等键时互相命中。
        return orderMapper.selectOne(Wrappers.<TradeOrder>lambdaQuery()
                .eq(TradeOrder::getUserId, userId).eq(TradeOrder::getIdempotencyKey, idempotencyKey));
    }

    /** 更新订单核心状态字段，并在变化时追加状态历史和审计。 */
    private boolean updateOrder(TradeOrder order, String targetStatus, String externalOrderNo,
                                BigDecimal filledQuantity, BigDecimal averageFilledPrice, String securityName,
                                String source, String brokerMessage) {
        // 先保存旧值，用于判断状态历史的previousStatus和是否真的发生变化。
        String previousStatus = order.getStatus();
        BigDecimal previousFilled = order.getFilledQuantity();
        BigDecimal previousAverage = order.getAverageFilledPrice();
        // 先计算变化再写库：对账接口往往重复返回同一行，不能每次都追加状态历史。
        boolean statusChanged = !Objects.equals(previousStatus, targetStatus);
        boolean filledChanged = filledQuantity != null && !sameDecimal(previousFilled, filledQuantity);
        boolean averageChanged = averageFilledPrice != null && !sameDecimal(previousAverage, averageFilledPrice);
        boolean externalChanged = externalOrderNo != null && !Objects.equals(order.getExternalOrderNo(), externalOrderNo);
        boolean nameChanged = securityName != null && !Objects.equals(order.getSecurityName(), securityName);
        // 重复轮询同一快照直接返回false，减少主表、历史和审计写入。
        if (!statusChanged && !filledChanged && !averageChanged && !externalChanged && !nameChanged) return false;

        // 只有调用方提供非null字段才覆盖主表，避免状态回报缺字段清空已有数据。
        if (targetStatus != null) order.setStatus(targetStatus);
        if (externalOrderNo != null) order.setExternalOrderNo(externalOrderNo);
        if (filledQuantity != null) order.setFilledQuantity(filledQuantity);
        if (averageFilledPrice != null) order.setAverageFilledPrice(averageFilledPrice);
        if (securityName != null) order.setSecurityName(securityName);
        // 所有有效变化共享一个更新时间，保证主表和状态历史时间可比较。
        LocalDateTime now = LocalDateTime.now();
        order.setUpdatedAt(now);
        // updateById只更新当前订单；状态历史和审计随后在同一事务中追加。
        orderMapper.updateById(order);
        // 证券名称或外部订单号变化也要更新主表，但只有会影响交易轨迹的字段变化才新增状态历史。
        if (statusChanged || filledChanged || averageChanged) {
            appendStatusHistory(order, previousStatus, source, brokerMessage, now);
        }
        return true;
    }

    /** 追加不可变订单状态观测记录。 */
    private void appendStatusHistory(TradeOrder order, String previousStatus, String source,
                                     String brokerMessage, LocalDateTime observedAt) {
        // 创建新的历史实体，不修改既有历史，保证时间线不可变。
        TradeOrderStatusHistory history = new TradeOrderStatusHistory();
        // 关联订单主键。
        history.setOrderId(order.getId());
        // 保存状态迁移前后的标准值；首次PENDING_SUBMIT的previousStatus为空。
        history.setPreviousStatus(previousStatus);
        history.setCurrentStatus(order.getStatus());
        // 历史快照记录当时累计成交量，未成交时统一保存0。
        history.setFilledQuantity(order.getFilledQuantity() == null ? BigDecimal.ZERO : order.getFilledQuantity());
        // 平均成交价允许为空，避免把“未成交”误显示为0元成交。
        history.setAverageFilledPrice(order.getAverageFilledPrice());
        // 来源用于区分WEB意图、QMT返回和后台轮询。
        history.setSource(source);
        // 券商消息写入前截断，防止异常长响应撑大历史表。
        history.setBrokerMessage(truncate(brokerMessage, 500));
        // observedAt来自调用方，确保与本次主表更新使用同一时间点。
        history.setObservedAt(observedAt);
        // createdAt是本地行创建时间；当前实现与观测时间保持一致。
        history.setCreatedAt(observedAt);
        // 插入后该状态观测永久保留。
        historyMapper.insert(history);
    }

    /** 追加不包含 Token、密码和完整账户信息的操作审计。 */
    private void appendAudit(TradeOrder order, String action, String idempotencyKey, String source,
                             Map<String, Object> detail, LocalDateTime now) {
        // 审计记录只追加不更新，形成高风险操作的不可变证据。
        TradeOrderAudit audit = new TradeOrderAudit();
        // 关联订单和所属用户，支持按订单/用户检索审计。
        audit.setOrderId(order.getId());
        audit.setUserId(order.getUserId());
        // action是稳定的机器可读事件名，前端可据此显示不同文案。
        audit.setAction(action);
        // 提交/撤单请求带幂等键，状态轮询和QMT回报则为空。
        audit.setIdempotencyKey(idempotencyKey);
        // source标记事件来自API、QMT或后台任务。
        audit.setSource(source);
        // json负责序列化并保持detail中的敏感数据已经在调用方被过滤。
        audit.setDetailJson(json(detail));
        // 保存事件发生时间。
        audit.setCreatedAt(now);
        // 在当前状态事务中插入审计，保证状态和审计不会一边成功一边失败。
        auditMapper.insert(audit);
    }

    /** 按主键读取订单内部实体。 */
    private TradeOrder require(Long orderId) {
        // 状态持久化方法只按主键读取，因为调用方已经在上层完成用户归属校验。
        TradeOrder order = orderMapper.selectById(orderId);
        // 订单在状态写入期间消失属于数据一致性异常，不应静默创建新订单。
        if (order == null) throw new IllegalStateException("订单状态写入时订单不存在: " + orderId);
        // 返回最新主表实体。
        return order;
    }

    /** 序列化审计详情。 */
    private String json(Map<String, Object> value) {
        try {
            // ObjectMapper统一生成JSON，避免手工拼接造成转义错误。
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            // 审计无法序列化时回滚当前事务，宁可拒绝状态变更也不写不完整审计。
            throw new IllegalStateException("订单审计JSON序列化失败", ex);
        }
    }

    /** 把券商状态转换为应用标准状态。 */
    private String status(Object value, String fallback) {
        // 先处理null/空白，再统一去空格和大写，兼容QMT返回的大小写差异。
        String result = nonBlank(value, fallback);
        return result == null ? fallback : result.trim().toUpperCase();
    }

    /** 读取非空文本，否则使用默认值。 */
    private String nonBlank(Object value, String fallback) {
        // String.valueOf兼容Map里放入的数字/枚举对象；空白值回退而不是写入空字符串。
        if (value == null || String.valueOf(value).isBlank()) return fallback;
        // 保留非空文本并去除首尾空格。
        return String.valueOf(value).trim();
    }

    /** 从弱类型券商字段读取 BigDecimal。 */
    private BigDecimal decimal(Object value) {
        // QMT未成交字段可能为null或空字符串，此时返回null表示“没有新观测”。
        if (value == null || String.valueOf(value).isBlank()) return null;
        try {
            // 通过字符串构造BigDecimal，避免double中间值产生精度误差。
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            // 单个脏数字不阻塞整轮对账；调用方会保留旧成交字段。
            return null;
        }
    }

    /** 判断累计成交数量是否已经覆盖全部委托数量。 */
    private boolean isFullyFilled(TradeOrder order, BigDecimal observedFilled) {
        // 优先使用本轮观测量，没有新观测时使用主表累计成交量。
        BigDecimal quantity = order.getQuantity();
        BigDecimal filled = observedFilled == null ? order.getFilledQuantity() : observedFilled;
        // 只有委托量为正且成交量大于等于委托量才判定全部成交。
        return quantity != null && quantity.signum() > 0 && filled != null && filled.compareTo(quantity) >= 0;
    }

    /** 忽略小数位差异比较两个可空十进制数。 */
    private boolean sameDecimal(BigDecimal left, BigDecimal right) {
        // BigDecimal.compareTo忽略scale差异，例如1.0和1.00视为同一数值。
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    /** 输出去尾零的十进制审计文本。 */
    private String decimalText(BigDecimal value) {
        // 审计中的数量不使用科学计数法，也不保留无意义尾零。
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    /** 截断可能过长的券商消息。 */
    private String truncate(String value, int max) {
        // 空消息转null；超长券商消息截断以满足数据库字段上限。
        if (value == null || value.isBlank()) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 将可空文本转换为空字符串，便于安全写入 JSON。 */
    private String safe(String value) {
        // Map.of不允许null值，审计详情中的可空文本统一转空字符串。
        return value == null ? "" : value;
    }

    /** 创建订单意图的幂等处理结果。 */
    public record PendingOrder(TradeOrder order, boolean newlyCreated) {
    }

    /** 撤单申请结果，区分首次派发、重复请求和不可撤状态。 */
    public record CancelRequest(TradeOrder order, boolean needsDispatch, boolean duplicate) {
    }
}
