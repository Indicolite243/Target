package com.stockmanager.account.live;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stockmanager.account.entity.Account;
import com.stockmanager.account.entity.Position;
import com.stockmanager.account.mapper.AccountMapper;
import com.stockmanager.account.mapper.PositionMapper;
import com.stockmanager.account.vo.AccountView;
import com.stockmanager.account.vo.PositionView;
import com.stockmanager.common.exception.BusinessException;
import com.stockmanager.integration.quant.QuantClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 实时组合服务：负责把 QMT 的“外部实时事实”转换成系统统一的组合快照。
 *
 * <p>这里刻意拆成两条链路：</p>
 * <ul>
 *     <li>采集链路：按账户从 QMT 读取数据，只写 Redis，保证页面能快速读取最新状态；</li>
 *     <li>持久化链路：独立任务消费 Redis 快照并写 MySQL，绝不因为落库再次调用 QMT。</li>
 * </ul>
 *
 * <p>这样做的关键原因是：QMT 是外部系统，调用耗时和可用性不可控；MySQL 是历史和
 * 降级数据源，不能被高频实时采集拖垮。</p>
 */
@Service
public class PortfolioLiveService {
    /** 记录自动采集、Redis 持久化等后台任务的可诊断信息。 */
    private static final Logger log = LoggerFactory.getLogger(PortfolioLiveService.class);

    /** 读取账户主表，用于校验账户归属和筛选需要接入 QMT 的账户。 */
    private final AccountMapper accountMapper;
    /** Redis 失效时读取 MySQL 当前持仓，组装离线降级快照。 */
    private final PositionMapper positionMapper;
    /** Java 到 FastAPI 的唯一访问边界，最终由 Python 适配器调用 xtquant/QMT。 */
    private final QuantClient quantClient;
    /** 封装 Redis 快照、数据版本以及账户级分布式刷新锁。 */
    private final PortfolioSnapshotCache snapshotCache;
    /** 把已经取得的实时快照写入 MySQL 当前表，不在事务中访问 QMT。 */
    private final PortfolioLivePersistenceService persistenceService;
    /** 同 JVM 内按账户互斥的锁集合，不同账户之间仍可并行采集。 */
    private final ConcurrentHashMap<Long, ReentrantLock> localLocks = new ConcurrentHashMap<>();
    /** 保存账户最近一次自动采集失败时间，用于在 QMT 离线时进行短暂退避。 */
    private final ConcurrentHashMap<Long, Long> automaticFailureAtMs = new ConcurrentHashMap<>();
    /** 保存最近一次告警时间，限制离线期间相同日志的输出频率。 */
    private final ConcurrentHashMap<Long, Long> failureLogAtMs = new ConcurrentHashMap<>();
    /** 自动采集失败后再次探测 QMT 前至少等待的毫秒数。 */
    private final long offlineProbeIntervalMs;

    /** 注入账户、持仓、Redis 快照缓存、MySQL 持久化边界和 FastAPI 客户端。 */
    public PortfolioLiveService(AccountMapper accountMapper, PositionMapper positionMapper,
                                QuantClient quantClient, PortfolioSnapshotCache snapshotCache,
                                PortfolioLivePersistenceService persistenceService,
                                @Value("${app.portfolio-live.offline-probe-interval-ms:5000}") long offlineProbeIntervalMs) {
        this.accountMapper = accountMapper;
        this.positionMapper = positionMapper;
        this.quantClient = quantClient;
        this.snapshotCache = snapshotCache;
        this.persistenceService = persistenceService;
        this.offlineProbeIntervalMs = offlineProbeIntervalMs;
    }

    /**
     * 页面读取的热路径：优先返回 Redis 快照。
     * Redis 缺失、过期或不可用时，才从 MySQL 当前表组装降级快照，并显式标记 stale，
     * 不能把旧数据伪装成实时数据。
     */
    public CurrentPortfolioSnapshot current(Long userId, Long accountId) {
        Account account = requireAccount(userId, accountId);
        return snapshotCache.get(accountId).orElseGet(() -> offlineSnapshot(account));
    }

    /**
     * 手动刷新是强制同步：先访问 QMT，再同时更新 Redis 和 MySQL，最后把本次快照返回给页面。
     * 只有这条“用户主动刷新”链路需要在 HTTP 请求结束前完成当前表持久化。
     */
    public CurrentPortfolioSnapshot refresh(Long userId, Long accountId, String traceId) {
        Account account = requireAccount(userId, accountId);
        CurrentPortfolioSnapshot snapshot = collectFromQmt(account, traceId, true);
        persistenceService.persistCurrentState(accountId, snapshot);
        return snapshot;
    }

    /**
     * 2 秒采集任务的入口。这里只做 QMT -> Redis，不写 MySQL，避免每次采集都产生数据库写放大。
     */
    public void collectAllQmtAccounts() {
        for (Account account : qmtAccounts()) {
            if (isInOfflineBackoff(account.getId())) continue;
            try {
                collectFromQmt(account, "live-collector-" + account.getId(), false);
                automaticFailureAtMs.remove(account.getId());
                failureLogAtMs.remove(account.getId());
            } catch (Exception ex) {
                long now = System.currentTimeMillis();
                automaticFailureAtMs.put(account.getId(), now);
                Long lastLogAt = failureLogAtMs.put(account.getId(), now);
                if (lastLogAt == null || now - lastLogAt >= offlineProbeIntervalMs) {
                    log.warn("QMT实时组合采集失败，进入{}ms离线探测间隔，accountId={}, reason={}",
                            offlineProbeIntervalMs, account.getId(), safeMessage(ex));
                }
            }
        }
    }

    /**
     * 30 秒持久化任务的入口。读取 Redis 中已经存在的快照写入 MySQL，不再触碰 QMT。
     * 即使 QMT 暂时离线，只要 Redis 中还有最后一份有效快照，本次仍可正常落库。
     */
    public void persistAllCachedQmtAccounts() {
        for (Account account : qmtAccounts()) {
            snapshotCache.get(account.getId()).ifPresent(snapshot -> {
                try {
                    persistenceService.persistCurrentState(account.getId(), snapshot);
                } catch (Exception ex) {
                    log.warn("实时组合持久化失败，accountId={}, reason={}", account.getId(), safeMessage(ex));
                }
            });
        }
    }

    /**
     * 历史快照写入器使用的内部读取接口。
     * 只返回 LIVE 且未标记过期的快照，避免把离线降级数据误写成新的实时历史点。
     */
    public List<CurrentPortfolioSnapshot> cachedQmtSnapshots() {
        return qmtAccounts().stream()
                .map(account -> snapshotCache.get(account.getId()))
                .flatMap(Optional::stream)
                .filter(snapshot -> !snapshot.stale() && "LIVE".equals(snapshot.mode()))
                .toList();
    }

    private CurrentPortfolioSnapshot collectFromQmt(Account account, String traceId, boolean manual) {
        /*
         * 一次刷新要同时保护两层并发：
         * 1. localLock：防止同一 JVM 内手动刷新和定时采集重叠；
         * 2. Redis token lock：防止多实例部署时不同 Spring 实例同时刷新同一账户。
         *
         * Redis 锁必须带 token，释放时还要校验 token，不能无条件 DEL，防止锁超时后
         * 被新持有者获得，旧请求完成时误删新锁。
         *
         * 时序：获取 JVM 锁 -> 获取 Redis 锁 -> 调用 QMT -> 写 Redis -> finally 释放两把锁。
         */
        // 为当前账户取得 JVM 内锁；computeIfAbsent 只在首次访问时创建锁对象。
        ReentrantLock localLock = localLocks.computeIfAbsent(account.getId(), ignored -> new ReentrantLock());
        // tryLock 是非阻塞式：已有刷新任务时立即返回 409，不占住 HTTP/调度线程排队等待。
        if (!localLock.tryLock()) {
            throw new BusinessException(409101, "该账户正在刷新，请稍后重试", HttpStatus.CONFLICT);
        }
        // 再获取 Redis 分布式锁；token 是当前持有者的唯一标识，避免多实例重复采集。
        String lockToken = snapshotCache.tryAcquireRefreshLock(account.getId(), Duration.ofSeconds(manual ? 5 : 3));
        if (lockToken == null) {
            // Redis 锁失败时，必须先释放已经取得的 JVM 锁，否则该账户会在本实例中永久不可刷新。
            localLock.unlock();
            throw new BusinessException(409101, "该账户正在刷新，请稍后重试", HttpStatus.CONFLICT);
        }
        try {
            // includeOrders=false：实时资产页只需要资产和持仓，不把订单查询混入每 2 秒采集。
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("accountId", String.valueOf(account.getId()));
            request.put("externalAccountId", account.getAccountNo());
            request.put("environment", account.getEnvironment());
            request.put("includePositions", true);
            request.put("includeOrders", false);
            // dataVersion 在每次成功采集时递增，前端可据此判断多个组件是否需要刷新。
            // QuantClient 会校验内部令牌、超时和 HTTP 状态；这里只处理成功返回的协议数据。
            CurrentPortfolioSnapshot snapshot = toLiveSnapshot(account,
                    quantClient.livePortfolio(request, traceId), snapshotCache.nextDataVersion(account.getId()));
            // 先形成一份完整不可变对象再单 key 覆盖，前端不会读到账户和持仓的“半个版本”。
            snapshotCache.put(snapshot);
            return snapshot;
        } finally {
            // 无论 QMT、协议转换还是 Redis 写入是否异常，两层锁都必须释放。
            snapshotCache.releaseRefreshLock(account.getId(), lockToken);
            localLock.unlock();
        }
    }

    /**
     * 把 Python 返回的松散 JSON Map 转换为 Java 强语义快照。
     * 必填字段缺失时抛出 502 协议异常，避免错误数据继续进入 Redis 和 MySQL。
     */
    private CurrentPortfolioSnapshot toLiveSnapshot(Account account, Map<String, Object> payload, long dataVersion) {
        // account 是资产摘要对象；positions 是与该资产时间点一致的完整持仓集合。
        Map<String, Object> asset = requiredMap(payload, "account");
        LocalDateTime snapshotTime = parseDateTime(requiredString(payload, "snapshotTime"));
        List<PositionView> positions = mapPositions(mapList(payload.get("positions"), "positions"));
        // source 缺失时以 qmt 兼容旧版本 Python 响应，并统一大写供后续判断。
        String source = optionalString(payload.get("source"), "qmt").toUpperCase();
        AccountView view = new AccountView(String.valueOf(account.getId()),
                optionalString(asset.get("externalAccountId"), account.getAccountNo()),
                masked(optionalString(asset.get("externalAccountId"), account.getAccountNo())),
                optionalString(asset.get("accountName"), account.getAccountName()),
                optionalString(asset.get("broker"), account.getBroker()),
                optionalString(asset.get("environment"), account.getEnvironment()),
                optionalString(asset.get("currency"), account.getCurrency()),
                money(requiredDecimal(asset, "totalAsset")), money(requiredDecimal(asset, "cash")),
                money(requiredDecimal(asset, "marketValue")), money(optionalDecimal(asset.get("profitLoss"))),
                snapshotTime, false, "ACTIVE");
        return new CurrentPortfolioSnapshot(String.valueOf(account.getId()), dataVersion, source,
                "LIVE", false, snapshotTime, 0L, view, positions, stringList(payload.get("warnings")));
    }

    /**
     * 使用 MySQL 当前表构造降级快照。
     * 该方法不会调用 QMT，也不会回写 Redis；stale=true 明确告诉页面这不是实时事实。
     */
    private CurrentPortfolioSnapshot offlineSnapshot(Account account) {
        // Redis 不可用时只读 MySQL 当前表；这里不尝试访问 QMT，避免故障时请求雪崩。
        // 查询 MySQL 当前持仓，并转换为与实时链路相同的前端视图结构。
        List<PositionView> positions = positionMapper.selectList(Wrappers.<Position>lambdaQuery()
                        .eq(Position::getAccountId, account.getId()))
                .stream().map(this::toPositionView).toList();
        LocalDateTime snapshotTime = account.getLastSyncTime();
        // 根据最后同步时间计算数据年龄；从未同步时使用 -1 表示未知。
        long ageMs = snapshotTime == null ? -1L : Math.max(0L,
                Duration.between(snapshotTime, LocalDateTime.now()).toMillis());
        // 把数据库 Account 转换成前端 AccountView；金融字段以字符串传输，避免浮点误差。
        // 账户 ID 同样转成字符串，避免 JavaScript 处理 19 位 Snowflake ID 时精度丢失。
        AccountView view = new AccountView(String.valueOf(account.getId()), account.getAccountNo(),
                masked(account.getAccountNo()), account.getAccountName(), account.getBroker(), account.getEnvironment(),
                account.getCurrency(), money(account.getTotalAsset()), money(account.getCash()),
                money(account.getMarketValue()), money(account.getProfitLoss()), snapshotTime, true, account.getStatus());

        // 使用 OFFLINE + stale 组合标记，让前端显示“最近一次同步数据”而不是“实时数据”。
        return new CurrentPortfolioSnapshot(String.valueOf(account.getId()),
                account.getDataVersion() == null ? 0L : account.getDataVersion(), "MYSQL", "OFFLINE", true,
                snapshotTime, ageMs, view, positions,
                List.of("QMT实时快照不可用，当前展示最近一次同步到MySQL的数据。"));
    }

    /** 查询所有启用的国金 QMT 账户，供采集、落库和历史任务共享同一筛选口径。 */
    private List<Account> qmtAccounts() {
        return accountMapper.selectList(Wrappers.<Account>lambdaQuery()
                .eq(Account::getBroker, "GUOJIN_QMT")
                .ne(Account::getStatus, "DISABLED"));
    }

    /** 判断账户是否仍处于自动采集失败后的退避窗口。手动刷新不受此窗口限制。 */
    private boolean isInOfflineBackoff(Long accountId) {
        Long failedAt = automaticFailureAtMs.get(accountId);
        return failedAt != null && System.currentTimeMillis() - failedAt < offlineProbeIntervalMs;
    }

    /**
     * 同时按 accountId 和 userId 查询账户。
     * userId 条件是资源归属校验，防止登录用户越权读取或刷新其他人的证券账户。
     */
    private Account requireAccount(Long userId, Long accountId) {
        Account account = accountMapper.selectOne(Wrappers.<Account>lambdaQuery()
                .eq(Account::getId, accountId).eq(Account::getUserId, userId));
        if (account == null) {
            throw new BusinessException(404101, "账户不存在", HttpStatus.NOT_FOUND);
        }
        return account;
    }

    /** 把 Python positions 数组逐项转换为不可变 PositionView 列表。 */
    private List<PositionView> mapPositions(List<Map<String, Object>> rows) {
        List<PositionView> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            // 证券代码和数量是契约必填项；价格、市值、行业等允许旧适配器暂时缺失。
            result.add(new PositionView(null, requiredString(row, "symbol"),
                    optionalString(row.get("securityName"), requiredString(row, "symbol")),
                    decimal(requiredDecimal(row, "quantity")), decimal(requiredDecimal(row, "availableQuantity")),
                    decimal(optionalDecimal(row.get("costPrice"))), decimal(optionalDecimal(row.get("lastPrice"))),
                    money(optionalDecimal(row.get("marketValue"))), money(optionalDecimal(row.get("profitLoss"))),
                    optionalString(row.get("industry"), null), optionalString(row.get("region"), null)));
        }
        return List.copyOf(result);
    }

    /** 把 MySQL 当前持仓实体转换成离线快照使用的展示对象。 */
    private PositionView toPositionView(Position position) {
        return new PositionView(String.valueOf(position.getId()), position.getSecurityCode(), position.getSecurityName(),
                decimal(position.getQuantity()), decimal(position.getAvailableQuantity()), decimal(position.getCostPrice()),
                decimal(position.getLastPrice()), money(position.getMarketValue()), money(position.getProfitLoss()),
                position.getIndustry(), position.getRegion());
    }

    /** 读取必填对象字段，并把任意键类型规范化为字符串键。 */
    private Map<String, Object> requiredMap(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof Map<?, ?> map)) throw contractError("缺少对象字段: " + key);
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((mapKey, mapValue) -> result.put(String.valueOf(mapKey), mapValue));
        return result;
    }

    /** 校验并转换 JSON 数组对象；null 按空数组兼容，不接受非对象元素。 */
    private List<Map<String, Object>> mapList(Object value, String field) {
        if (value == null) return List.of();
        if (!(value instanceof Collection<?> collection)) throw contractError("字段不是数组: " + field);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : collection) {
            if (!(item instanceof Map<?, ?> map)) throw contractError("数组元素不是对象: " + field);
            Map<String, Object> converted = new LinkedHashMap<>();
            map.forEach((key, itemValue) -> converted.put(String.valueOf(key), itemValue));
            result.add(converted);
        }
        return result;
    }

    /** 读取非空必填文本字段，失败时统一抛出上游协议异常。 */
    private String requiredString(Map<String, Object> source, String key) {
        String value = optionalString(source.get(key), null);
        if (value == null || value.isBlank()) throw contractError("缺少文本字段: " + key);
        return value;
    }

    /** 将可选值文本化；null 或空白时使用调用方提供的默认值。 */
    private String optionalString(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    /** 读取必填金融数值，既接受 JSON number，也接受避免精度损失的 JSON string。 */
    private BigDecimal requiredDecimal(Map<String, Object> source, String key) {
        if (!source.containsKey(key) || source.get(key) == null) throw contractError("缺少数值字段: " + key);
        return decimalValue(source.get(key), key);
    }

    /** 读取可选金融数值；缺失时按零兼容旧协议。 */
    private BigDecimal optionalDecimal(Object value) {
        return value == null || String.valueOf(value).isBlank() ? BigDecimal.ZERO : decimalValue(value, "optional");
    }

    /** 使用字符串构造 BigDecimal，禁止经过 double 中转而引入二进制浮点误差。 */
    private BigDecimal decimalValue(Object value, String field) {
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw contractError("非法数值字段: " + field);
        }
    }

    /** 兼容带时区和不带时区的 ISO-8601 时间，并统一转换为服务本地时间。 */
    private LocalDateTime parseDateTime(String value) {
        try {
            return OffsetDateTime.parse(value).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value);
            } catch (DateTimeParseException ex) {
                throw contractError("非法时间字段: snapshotTime");
            }
        }
    }

    /** 把可选警告数组规范化为字符串列表；非数组值按无警告处理。 */
    private List<String> stringList(Object value) {
        if (!(value instanceof Collection<?> collection)) return List.of();
        return collection.stream().map(String::valueOf).toList();
    }

    /** 构造“Python 响应不符合内部契约”的 502 业务异常。 */
    private BusinessException contractError(String detail) {
        return new BusinessException(502601, "Python实时组合数据结构错误：" + detail, HttpStatus.BAD_GATEWAY);
    }

    /** 普通数量格式化：保留有效小数并去除无意义尾零。 */
    private String decimal(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    /** 金额格式化：固定两位小数，使用 HALF_UP 规则。 */
    private String money(BigDecimal value) {
        return value == null ? "0.00" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 账户号脱敏，只向页面暴露末四位。 */
    private String masked(String accountNo) {
        return accountNo == null ? null : "****" + accountNo.substring(Math.max(0, accountNo.length() - 4));
    }

    /** 后台日志只提取安全的异常摘要，避免手工拼接完整堆栈。 */
    private String safeMessage(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
