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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 订单应用服务实现，协调 MySQL 订单意图、QMT 外部副作用以及后台状态对账。
 *
 * <p>交易链路采用“本地意图先落库、事务外调用 QMT、结果再次短事务落库”的结构。
 * {@link OrderStatePersistenceService} 封装状态变更与审计写入，本类负责业务编排、权限校验、
 * 幂等语义和上游请求组装。</p>
 *
 * <p>任何网络超时都不能直接重试下单：超时可能意味着 QMT 已受理但响应丢失，自动重试会产生
 * 重复委托。因此不确定结果必须进入 UNKNOWN，并由后台对账收敛。</p>
 */
@Service
public class OrderServiceImpl implements OrderService {
    private final TradeOrderMapper orderMapper;
    private final TradeOrderStatusHistoryMapper historyMapper;
    private final TradeOrderAuditMapper auditMapper;
    private final AccountMapper accountMapper;
    private final QuantClient quantClient;
    private final OrderStatePersistenceService statePersistenceService;
    private final ObjectMapper objectMapper;

    /** 通过构造器注入订单表、审计表、账户表、QMT 客户端和状态持久化边界。 */
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

    /**
     * 提交一笔 QMT 委托。
     *
     * <p>流程被刻意切成三个阶段：</p>
     * <ol>
     *     <li>查询幂等键，并在短事务中创建本地 {@code PENDING_SUBMIT} 意图；</li>
     *     <li>事务外调用 QMT，避免网络等待占用数据库连接和事务；</li>
     *     <li>再次开启短事务，把“成功、明确拒绝或结果不确定”写回本地。</li>
     * </ol>
     *
     * <p>外部系统调用无法参加 MySQL 事务，所以网络超时不能简单当作失败重试，必须进入
     * {@code UNKNOWN}，等待后台按外部订单号对账，防止重复下单。</p>
     */
    @Override
    public OrderView submit(Long userId, String idempotencyKey, SubmitOrderRequest request, String traceId) {
        // 幂等查询是性能优化；真正的并发兜底仍由数据库唯一索引负责。
//        检查幂等键
        TradeOrder existing = statePersistenceService.findByIdempotency(userId, idempotencyKey);
        if (existing != null) return toView(existing, "返回首次幂等处理结果");
//        校验账户归属
        Account account = requireAccount(userId, Long.valueOf(request.accountId()));
        //校验账户有效性
        validateRequest(account, request);
        TradeOrder draft = newOrder(userId, idempotencyKey, account, request);
        // createPending 内部同时写订单、初始状态历史和审计，确保本地“下单意图”完整落库。
        OrderStatePersistenceService.PendingOrder pending = statePersistenceService.createPending(draft, traceId);
        if (!pending.newlyCreated()) return toView(pending.order(), "返回首次幂等处理结果");

        try {
            // 这里明确不在 @Transactional 方法中：QMT 的响应时间不应决定 MySQL 事务时长。
            TradeOrder accepted = statePersistenceService.applySubmitAccepted(pending.order().getId(),
                    quantClient.submitOrder(submitPayload(pending.order(), account), traceId), traceId);
            return toView(accepted, "UNKNOWN".equals(accepted.getStatus()) ? "委托状态确认中" : "委托已受理");
        } catch (BusinessException ex) {
            // 4xx 代表 QMT 明确拒绝，可以安全落 REJECTED；其他异常可能是“QMT 已受理但响应丢失”，
            // 只能落 UNKNOWN，交给轮询对账，不允许这里自动再次 submitOrder。
            TradeOrder latest = ex.getStatus().is4xxClientError()
                    ? statePersistenceService.markSubmitRejected(pending.order().getId(), traceId, ex.getMessage())
                    : statePersistenceService.markSubmitUnknown(pending.order().getId(), traceId, ex.getMessage());
            return toView(latest, "REJECTED".equals(latest.getStatus()) ? "委托被QMT拒绝" : "委托状态确认中");
        }
    }

    /**
     * 查询本地订单历史。
     *
     * <p>列表接口只查 MySQL，不在用户刷新页面时临时访问 QMT。这样页面延迟不会被券商接口
     * 拖慢，且所有用户看到的列表都经过统一的本地权限、日期和软删除过滤。</p>
     */
    @Override
    public OrderPageView list(Long userId, Long accountId, long page, long pageSize,
                              LocalDateTime start, LocalDateTime endExclusive) {
        // 页码从1开始，防止MyBatis-Plus收到0或负数造成无意义查询。
        long safePage = Math.max(1, page);
        // 限制单页最多100条，避免前端传入超大pageSize拖慢数据库和JSON序列化。
        long safePageSize = Math.clamp(pageSize, 1, 100);
        // 所有订单查询先按user_id隔离；后续accountId只是该用户范围内的附加过滤。
        var query = Wrappers.<TradeOrder>lambdaQuery().eq(TradeOrder::getUserId, userId);
        // accountId为空表示查询该用户全部账户，否则只查询指定账户。
        if (accountId != null) query.eq(TradeOrder::getAccountId, accountId);
        // 软删除记录不再展示，但数据库中的主表、状态历史和审计仍然保留。
        query.isNull(TradeOrder::getDeletedAt);
        // 开始时间使用大于等于，包含当天零点的订单。
        if (start != null) query.ge(TradeOrder::getCreatedAt, start);
        // 结束时间使用小于次日零点的开区间，避免LocalDate只到当天00:00的问题。
        if (endExclusive != null) query.lt(TradeOrder::getCreatedAt, endExclusive);
        // 过滤历史脏数据：必须有证券代码和正数量，并按创建时间倒序展示最新订单。
        query.isNotNull(TradeOrder::getSymbol).ne(TradeOrder::getSymbol, "")
                .gt(TradeOrder::getQuantity, BigDecimal.ZERO).orderByDesc(TradeOrder::getCreatedAt);
        // MyBatis-Plus生成带LIMIT/OFFSET的分页SQL，并返回总数、页数和当前页记录。
        Page<TradeOrder> dbPage = orderMapper.selectPage(new Page<>(safePage, safePageSize), query);
        // Entity不能直接暴露给前端；toView负责ID/金额字符串化和字段裁剪。
        List<OrderView> items = dbPage.getRecords().stream().map(order -> toView(order, null)).toList();
        // 将MyBatis-Plus分页结果转换成前端固定的OrderPageView协议。
        return new OrderPageView(items, dbPage.getCurrent(), dbPage.getSize(), dbPage.getTotal(),
                dbPage.getPages(), dbPage.hasNext());
    }

    /**
     * 软删除一条已结束的本地历史订单。删除只是隐藏展示记录，不撤销 QMT 委托，也不删除状态历史。
     */
    @Override
    @Transactional
    public void deleteHistory(Long userId, Long orderId, String traceId) {
        // 同时按userId和orderId读取，避免用户猜测ID后读取或删除他人订单。
        TradeOrder order = requireOrder(userId, orderId);
        // 重复删除是幂等操作，不重复更新数据库和写审计。
        if (order.getDeletedAt() != null) return;
        // 进行中订单可能仍会成交或需要撤单，禁止仅通过“删除历史”隐藏它。
        if (OrderStatusPolicy.isTrackable(order.getStatus())) {
            throw new BusinessException(409303, "进行中的委托不能删除，请先撤单或等待终态", HttpStatus.CONFLICT);
        }
        // 终态订单只写deleted_at并追加审计，不调用QMT撤单接口。
        markHistoryDeleted(order, userId, traceId);
    }

    /**
     * 按日期条件批量隐藏终态订单。进行中的订单被条件过滤，避免用户用“清空历史”误删待处理委托。
     */
    @Override
    @Transactional
    public int deleteFilteredHistory(Long userId, LocalDateTime start, LocalDateTime endExclusive, String traceId) {
        // 批量操作同样先限定用户、未删除、有效证券和正数量。
        var query = Wrappers.<TradeOrder>lambdaQuery().eq(TradeOrder::getUserId, userId)
                .isNull(TradeOrder::getDeletedAt)
                .isNotNull(TradeOrder::getSymbol).ne(TradeOrder::getSymbol, "")
                .gt(TradeOrder::getQuantity, BigDecimal.ZERO)
                .notIn(TradeOrder::getStatus, OrderStatusPolicy.trackableStoredStates());
        // 时间范围沿用Controller的开区间约定。
        if (start != null) query.ge(TradeOrder::getCreatedAt, start);
        if (endExclusive != null) query.lt(TradeOrder::getCreatedAt, endExclusive);
        // notIn排除所有可跟踪状态，只取终态/历史脏状态，避免批量误删在途委托。
        List<TradeOrder> orders = orderMapper.selectList(query);
        // 每条记录单独写deleted_at和DELETE_HISTORY审计，保留逐单可追溯事实。
        orders.forEach(order -> markHistoryDeleted(order, userId, traceId));
        // 返回实际处理条数，Controller直接向前端展示。
        return orders.size();
    }

    /**
     * 将一条终态订单标记为本地历史已删除，并追加不可变审计记录。
     */
    private void markHistoryDeleted(TradeOrder order, Long userId, String traceId) {
        // 主订单只写 deleted_at；审计记录保留“谁在何时隐藏了它”的事实，满足可追溯要求。
        LocalDateTime now = LocalDateTime.now();
        order.setDeletedAt(now);
        // updated_at反映主表最近一次修改，即使状态没有变化也要记录软删除时间点。
        order.setUpdatedAt(now);
        // updateById只更新该订单主表，不物理删除数据。
        orderMapper.updateById(order);
        // 审计记录使用订单所属用户，系统不会接受前端传入的任意userId。
        TradeOrderAudit audit = new TradeOrderAudit();
        audit.setOrderId(order.getId());
        audit.setUserId(userId);
        audit.setAction("DELETE_HISTORY");
        // 历史隐藏不是幂等请求链路，此处没有单独的幂等键。
        audit.setIdempotencyKey(null);
        audit.setSource("WEB");
        audit.setDetailJson(objectMapper.createObjectNode().put("traceId", traceId == null ? "" : traceId).toString());
        audit.setCreatedAt(now);
        // 审计与主表软删除处于同一事务，任一写入失败都会整体回滚。
        auditMapper.insert(audit);
    }

    /**
     * 发起撤单。
     *
     * <p>先把本地状态改成 {@code CANCEL_PENDING}，再在事务外调用 QMT。QMT 返回“撤单请求成功”
     * 不等于最终撤成，最终状态仍由三秒对账决定；撤单期间如果先成交，状态应收敛为 FILLED。</p>
     */
    @Override
    public OrderView cancel(Long userId, Long orderId, String idempotencyKey, String traceId) {
        // 先做用户归属校验；requestCancel内部还会再次读取最新订单状态。
        requireOrder(userId, orderId);
        // 在短事务中原子记录CANCEL_REQUESTED并把订单置为CANCEL_PENDING。
        OrderStatePersistenceService.CancelRequest request = statePersistenceService.requestCancel(orderId,
                idempotencyKey, traceId);
        // duplicate表示同一个撤单幂等键已处理过，直接返回原订单快照。
        if (!request.needsDispatch()) {
            if (request.duplicate()) return toView(request.order(), "返回首次幂等处理结果");
            // 非可撤状态可能已成交、已撤或仍在撤单中，不能向QMT重复发送撤单。
            throw new BusinessException(409302, "当前订单状态不允许撤单", HttpStatus.CONFLICT);
        }

        try {
            // 此处仍不持有MySQL事务；撤单是外部副作用，无法由数据库回滚。
            Account account = requireAccount(userId, request.order().getAccountId());
            // 撤单负载同时携带本地订单号、QMT外部订单号和账户号，Quant服务据此定位原委托。
            TradeOrder accepted = statePersistenceService.applyCancelAccepted(orderId,
                    quantClient.cancelOrder(cancelPayload(request.order(), account, idempotencyKey), traceId), traceId);
            // “撤单请求已受理”不是“最终已撤成”；后台对账仍可能收敛为FILLED。
            return toView(accepted, "撤单请求已受理");
        } catch (BusinessException ex) {
            // 撤单异常结果同样不能自动重试；保留CANCEL_PENDING并记录原因，等待下一轮对账。
            TradeOrder latest = statePersistenceService.markCancelUnconfirmed(orderId, traceId, ex.getMessage());
            return toView(latest, "撤单状态确认中");
        }
    }

    /**
     * 订单状态对账：一次 QMT 查询服务当前账户下的全部未终态订单。
     *
     * <p>本地订单先筛出有 externalOrderNo 且仍需跟踪的记录，再按外部订单号建索引，避免
     * “每个订单调用一次 QMT”。只有状态、成交数量或成交均价真正变化时才写库。</p>
     *
     * <p>QMT 暂时不可用时保留最后一次已确认的 MySQL 状态，不能因为一次网络失败把订单改成失败。</p>
     */
    @Override
    public int refreshOpenOrderStatuses() {
        // 先找出所有启用且由GUOJIN_QMT经纪商管理的账户；定时任务不会访问禁用账户。
        Set<Long> qmtAccountIds = accountMapper.selectList(Wrappers.<Account>lambdaQuery()
                        .eq(Account::getBroker, "GUOJIN_QMT")
                        .ne(Account::getStatus, "DISABLED"))
                .stream().map(Account::getId).collect(Collectors.toSet());
        // 没有可用账户时直接结束，避免无意义的quant-service请求。
        if (qmtAccountIds.isEmpty()) return 0;
        // 只取有外部订单号且仍需跟踪的订单；终态订单不再占用QMT查询和数据库更新资源。
        List<TradeOrder> orders = orderMapper.selectList(Wrappers.<TradeOrder>lambdaQuery()
                .in(TradeOrder::getAccountId, qmtAccountIds)
                .in(TradeOrder::getStatus, OrderStatusPolicy.trackableStoredStates())
                .isNotNull(TradeOrder::getExternalOrderNo));
        // 没有在途订单时直接返回，轮询空转不会产生QMT访问。
        if (orders.isEmpty()) return 0;

        try {
            // 一次调用获取当前账户下的订单快照，而不是每个本地订单单独查询，降低网络往返。
            Object raw = quantClient.queryOrders("order-status-poller").get("orders");
            // 外部协议不是List时视为本轮无可应用数据，保留本地最近确认状态。
            if (!(raw instanceof List<?> rows)) return 0;
            // 按外部订单号建立索引，把每个本地订单的查找从线性扫描降为O(1)。
            Map<String, Map<?, ?>> qmtByOrderNo = new LinkedHashMap<>();
            for (Object row : rows) {
                // 仅接受带externalOrderNo的Map记录，忽略QMT返回的其它元数据行。
                if (row instanceof Map<?, ?> map && map.get("externalOrderNo") != null) {
                    qmtByOrderNo.put(String.valueOf(map.get("externalOrderNo")), map);
                }
            }
            // changed统计实际发生状态/成交变化的订单数，用于日志和调度监控。
            int changed = 0;
            // 跨日缺失收敛规则需要当前自然日，不能用订单创建日或JVM默认日期缓存。
            LocalDate currentDate = LocalDate.now();
            for (TradeOrder order : orders) {
                // 通过QMT外部订单号匹配本地订单；本地ID不能直接用于券商查询。
                Map<?, ?> qmt = qmtByOrderNo.get(order.getExternalOrderNo());
                // 找到快照时交给状态持久化服务完成状态归一化、幂等更新和历史审计。
                if (qmt != null && statePersistenceService.applyBrokerStatus(order.getId(), qmt, "order-status-poller")) {
                    changed++;
                // 找不到快照只对“昨日以前发起撤单”的订单做安全跨日收敛，普通订单不能凭缺失判定终态。
                } else if (qmt == null && OrderStatusPolicy.shouldReconcileMissingPriorDayCancellation(
                        order.getStatus(), order.getCreatedAt(), currentDate)
                        && statePersistenceService.reconcileMissingPriorDayCancellation(
                        order.getId(), currentDate, "order-status-poller")) {
                        changed++;
                }
            }
            // 本轮结束后返回变化数；状态未变化的订单不会写库。
            return changed;
        } catch (BusinessException ignored) {
            // MiniQMT不可用或响应异常时保留MySQL最近确认状态，下一轮继续尝试，不能批量改成失败。
            return 0;
        }
    }

    /**
     * 查询订单完整时间线，包括状态变化与脱敏后的操作审计。
     */
    @Override
    public OrderTimelineView timeline(Long userId, Long orderId) {
        // 先校验订单属于当前用户；通过后才读取状态历史和审计，避免越权时间线查询。
        requireOrder(userId, orderId);
        // 状态历史按观测时间升序读取，保证前端看到从提交到当前的时间顺序。
        List<OrderTimelineView.StatusItem> statusHistory = historyMapper.selectList(
                        Wrappers.<TradeOrderStatusHistory>lambdaQuery().eq(TradeOrderStatusHistory::getOrderId, orderId)
                                .orderByAsc(TradeOrderStatusHistory::getObservedAt))
                // Entity中的BigDecimal转换为字符串，保持金额/数量精度并统一前端协议。
                .stream().map(item -> new OrderTimelineView.StatusItem(item.getPreviousStatus(), item.getCurrentStatus(),
                        decimal(item.getFilledQuantity()), decimal(item.getAverageFilledPrice()), item.getSource(),
                        item.getBrokerMessage(), item.getObservedAt())).toList();
        // 审计按写入时间升序读取；auditDetail会解析JSON并在异常时返回空对象。
        List<OrderTimelineView.AuditItem> audit = auditMapper.selectList(
                        Wrappers.<TradeOrderAudit>lambdaQuery().eq(TradeOrderAudit::getOrderId, orderId)
                                .orderByAsc(TradeOrderAudit::getCreatedAt))
                .stream().map(item -> new OrderTimelineView.AuditItem(item.getAction(), item.getSource(),
                        auditDetail(item.getDetailJson()), item.getCreatedAt())).toList();
        // 返回组合时间线；幂等键等内部定位字段不会进入AuditItem。
        return new OrderTimelineView(String.valueOf(orderId), statusHistory, audit);
    }

    /**
     * 根据已校验的请求构造尚未落库的订单意图实体。
     *
     * <p>该方法只做DTO到Entity的字段映射，不设置主键、clientOrderNo、状态和时间；
     * 那些由OrderStatePersistenceService在事务中统一生成。</p>
     */
    private TradeOrder newOrder(Long userId, String idempotencyKey, Account account, SubmitOrderRequest request) {
        // 创建空订单实体，MyBatis-Plus稍后负责生成ASSIGN_ID主键。
        TradeOrder order = new TradeOrder();
        // 保存数据库账户主键，便于权限隔离和后台按账户批量对账。
        order.setAccountId(account.getId());
        // 保存请求用户主键，幂等索引和所有查询都依赖该字段。
        order.setUserId(userId);
        // 保存用户级幂等键，createPending会使用它防止重复本地订单意图。
        order.setIdempotencyKey(idempotencyKey);
        // 证券代码保留请求值；QMT适配器调用时再去空格并转大写。
        order.setSymbol(request.symbol());
        // 名称为空时用代码作为兜底，保证列表和审计至少有可识别文本。
        order.setSecurityName(request.securityName() == null || request.securityName().isBlank()
                ? request.symbol() : request.securityName());
        // 保存可读的BUY/SELL，数字枚举转换只发生在quant-service边界。
        order.setSide(request.side());
        // 保存可读的LIMIT/MARKET，便于页面展示和重新组装请求。
        order.setOrderType(request.orderType());
        // 字符串数量转换成BigDecimal，避免先经过double造成金额精度损失。
        order.setQuantity(new BigDecimal(request.quantity()));
        // 空价格表示市价单；限价单价格已在validateRequest中校验为正数。
        order.setPrice(request.price() == null || request.price().isBlank() ? null : new BigDecimal(request.price()));
        // 保存环境，之后由quant-service再次检查是否允许该环境交易。
        order.setEnvironment(request.environment());
        // 备注只用于业务展示，不参与外部订单定位。
        order.setRemark(request.remark());
        // 返回尚未落库的订单意图，交给createPending补齐状态和时间。
        return order;
    }

    /** 校验账户环境、订单类型和价格之间的业务约束。 */
    private void validateRequest(Account account, SubmitOrderRequest request) {
        // 订单环境必须和账户环境严格一致，避免SIMULATION/REAL串线。
        if (!account.getEnvironment().equals(request.environment())) {
            throw new BusinessException(422301, "委托环境与账户环境不一致", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        // 空价格保留为null；有值时用BigDecimal解析，拒绝非法数字。
        BigDecimal price = request.price() == null || request.price().isBlank() ? null : new BigDecimal(request.price());
        // LIMIT必须有正价格；MARKET允许没有价格，适配器会按QMT约定传0。
        if ("LIMIT".equals(request.orderType()) && (price == null || price.signum() <= 0)) {
            throw new BusinessException(422301, "限价单价格必须大于0", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    /**
     * 组装发送给FastAPI/QMT的下单负载，数值均使用十进制字符串传输。
     *
     * <p>LinkedHashMap让序列化字段顺序稳定，便于日志比对；具体HTTP请求由QuantClient完成。</p>
     */
    private Map<String, Object> submitPayload(TradeOrder order, Account account) {
        // 使用有序Map构造跨服务协议，避免直接把MyBatis实体暴露给quant-service。
        Map<String, Object> payload = new LinkedHashMap<>();
        // 本地Snowflake订单ID，供FastAPI日志和异常排查关联。
        payload.put("orderId", String.valueOf(order.getId()));
        // 应用订单号是幂等/审计链路可读标识，也会写入QMT备注。
        payload.put("clientOrderNo", order.getClientOrderNo());
        // QMT外部资金账号来自已校验的Account，而不是来自前端请求体。
        payload.put("externalAccountId", account.getAccountNo());
        // 证券代码、方向和委托类型使用应用层可读字符串。
        payload.put("symbol", order.getSymbol());
        payload.put("side", order.getSide());
        payload.put("orderType", order.getOrderType());
        // 数量用十进制文本传输，避免JSON浮点精度误差。
        payload.put("quantity", order.getQuantity().toPlainString());
        // 市价单price为null；限价单保留原始BigDecimal文本。
        payload.put("price", order.getPrice() == null ? null : order.getPrice().toPlainString());
        // 交易环境供FastAPI最后一道安全校验使用。
        payload.put("environment", order.getEnvironment());
        // 返回协议Map，QuantClient序列化后发给quant-service。
        return payload;
    }

    /**
     * 组装撤单负载，同时携带本地、客户端和券商三类订单标识。
     *
     * <p>撤单不能只依赖本地ID：quant-service需要externalOrderNo定位QMT订单，
     * accountNo则决定在哪个资金账户执行撤单。</p>
     */
    private Map<String, Object> cancelPayload(TradeOrder order, Account account, String idempotencyKey) {
        // 与下单一样使用有序Map，形成稳定的跨服务JSON结构。
        Map<String, Object> payload = new LinkedHashMap<>();
        // 本地订单ID用于Spring和FastAPI日志关联。
        payload.put("orderId", String.valueOf(order.getId()));
        // clientOrderNo用于人工排查和QMT备注关联。
        payload.put("clientOrderNo", order.getClientOrderNo());
        // 外部订单号是QMT撤单的核心定位字段，缺失时传空字符串由下游明确报错。
        payload.put("externalOrderNo", order.getExternalOrderNo() == null ? "" : order.getExternalOrderNo());
        // 账户号取自数据库账户实体，不信任前端覆盖。
        payload.put("externalAccountId", account.getAccountNo());
        // 环境必须和原订单一致，防止撤单请求跨环境执行。
        payload.put("environment", order.getEnvironment());
        // 撤单幂等键由quant-service记录/透传，确保网络重试不会重复撤单。
        payload.put("idempotencyKey", idempotencyKey);
        // 返回撤单协议Map。
        return payload;
    }

    /** 按用户归属查询订单，防止通过订单ID访问其他用户数据。 */
    private TradeOrder requireOrder(Long userId, Long orderId) {
        // SQL同时使用主键和user_id条件，形成应用层资源隔离。
        TradeOrder order = orderMapper.selectOne(Wrappers.<TradeOrder>lambdaQuery()
                .eq(TradeOrder::getId, orderId).eq(TradeOrder::getUserId, userId));
        // 不暴露“订单存在但属于别人”的差异，统一返回不存在。
        if (order == null) throw new BusinessException(404301, "订单不存在", HttpStatus.NOT_FOUND);
        // 返回通过归属校验的实体。
        return order;
    }

    /** 按用户归属查询交易账户。 */
//    SELECT *
//    FROM account
//    WHERE id = ?
//    AND user_id = ?;
    private Account requireAccount(Long userId, Long accountId) {
        // 账户查询同样绑定user_id，防止把他人的QMT资金账号用于下单。
        Account account = accountMapper.selectOne(Wrappers.<Account>lambdaQuery()
                .eq(Account::getId, accountId).eq(Account::getUserId, userId));
        // 账户不存在、被删除或不属于用户时统一返回404。
        if (account == null) throw new BusinessException(404101, "账户不存在", HttpStatus.NOT_FOUND);
        // 返回已完成归属校验的账户实体。
        return account;
    }

    /** 把数据库订单转换为前端稳定模型，Snowflake ID始终输出字符串。 */
    private OrderView toView(TradeOrder order, String message) {
        // 组装不可变record，避免前端直接看到MyBatis实体和内部字段。
        return new OrderView(String.valueOf(order.getId()), order.getClientOrderNo(), order.getExternalOrderNo(),
                String.valueOf(order.getAccountId()), order.getSymbol(), order.getSecurityName(), order.getSide(),
                order.getOrderType(), decimal(order.getQuantity()), decimal(order.getPrice()),
                decimal(order.getFilledQuantity()), decimal(order.getAverageFilledPrice()), order.getStatus(),
                order.getEnvironment(), order.getCreatedAt(), order.getUpdatedAt(), message);
    }

    /** 按原始精度输出数量和价格，避免二进制浮点误差。 */
    private String decimal(BigDecimal value) {
        // null保留null；非空值去掉无意义尾零后用十进制文本输出。
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    /**
     * 解析审计 JSON。旧数据或异常数据解析失败时返回空对象，避免时间线整体不可用。
     */
    private Map<String, Object> auditDetail(String value) {
        // 没有详情时返回不可变空Map，保证前端字段始终是对象而非null。
        if (value == null || value.isBlank()) return Map.of();
        try {
            // 解析历史JSON为通用Map，兼容不同版本审计字段。
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (Exception ignored) {
            // 单条脏审计不应阻塞整条订单时间线，解析失败以空对象降级。
            return Map.of();
        }
    }
}
