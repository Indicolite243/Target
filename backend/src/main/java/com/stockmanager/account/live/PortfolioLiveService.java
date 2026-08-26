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
 * Coordinates current portfolio reads. Automatic collection creates Redis
 * snapshots only; MySQL persistence is separately scheduled and never triggers
 * a second QMT request.
 */
@Service
public class PortfolioLiveService {
    private static final Logger log = LoggerFactory.getLogger(PortfolioLiveService.class);

    private final AccountMapper accountMapper;
    private final PositionMapper positionMapper;
    private final QuantClient quantClient;
    private final PortfolioSnapshotCache snapshotCache;
    private final PortfolioLivePersistenceService persistenceService;
    private final ConcurrentHashMap<Long, ReentrantLock> localLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> automaticFailureAtMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> failureLogAtMs = new ConcurrentHashMap<>();
    private final long offlineProbeIntervalMs;

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

    /** Hot path: Redis only. An expired/missing cache returns explicitly stale MySQL data. */
    public CurrentPortfolioSnapshot current(Long userId, Long accountId) {
        Account account = requireAccount(userId, accountId);
        return snapshotCache.get(accountId).orElseGet(() -> offlineSnapshot(account));
    }

    /** Manual refresh always reaches QMT, then updates Redis and MySQL before returning. */
    public CurrentPortfolioSnapshot refresh(Long userId, Long accountId, String traceId) {
        Account account = requireAccount(userId, accountId);
        CurrentPortfolioSnapshot snapshot = collectFromQmt(account, traceId, true);
        persistenceService.persistCurrentState(accountId, snapshot);
        return snapshot;
    }

    /** Called by the two-second scheduler. It does not write MySQL. */
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

    /** Called every 30 seconds. It writes the latest Redis snapshot without touching QMT. */
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

    /** Internal history writers consume these Redis snapshots; no QMT call is made here. */
    public List<CurrentPortfolioSnapshot> cachedQmtSnapshots() {
        return qmtAccounts().stream()
                .map(account -> snapshotCache.get(account.getId()))
                .flatMap(Optional::stream)
                .filter(snapshot -> !snapshot.stale() && "LIVE".equals(snapshot.mode()))
                .toList();
    }

    private CurrentPortfolioSnapshot collectFromQmt(Account account, String traceId, boolean manual) {
        ReentrantLock localLock = localLocks.computeIfAbsent(account.getId(), ignored -> new ReentrantLock());
        if (!localLock.tryLock()) {
            throw new BusinessException(409101, "该账户正在刷新，请稍后重试", HttpStatus.CONFLICT);
        }
        String lockToken = snapshotCache.tryAcquireRefreshLock(account.getId(), Duration.ofSeconds(manual ? 5 : 3));
        if (lockToken == null) {
            localLock.unlock();
            throw new BusinessException(409101, "该账户正在刷新，请稍后重试", HttpStatus.CONFLICT);
        }
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("accountId", String.valueOf(account.getId()));
            request.put("externalAccountId", account.getAccountNo());
            request.put("environment", account.getEnvironment());
            request.put("includePositions", true);
            request.put("includeOrders", false);
            CurrentPortfolioSnapshot snapshot = toLiveSnapshot(account,
                    quantClient.livePortfolio(request, traceId), snapshotCache.nextDataVersion(account.getId()));
            snapshotCache.put(snapshot);
            return snapshot;
        } finally {
            snapshotCache.releaseRefreshLock(account.getId(), lockToken);
            localLock.unlock();
        }
    }

    private CurrentPortfolioSnapshot toLiveSnapshot(Account account, Map<String, Object> payload, long dataVersion) {
        Map<String, Object> asset = requiredMap(payload, "account");
        LocalDateTime snapshotTime = parseDateTime(requiredString(payload, "snapshotTime"));
        List<PositionView> positions = mapPositions(mapList(payload.get("positions"), "positions"));
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

    private CurrentPortfolioSnapshot offlineSnapshot(Account account) {
        List<PositionView> positions = positionMapper.selectList(Wrappers.<Position>lambdaQuery()
                        .eq(Position::getAccountId, account.getId()))
                .stream().map(this::toPositionView).toList();
        LocalDateTime snapshotTime = account.getLastSyncTime();
        long ageMs = snapshotTime == null ? -1L : Math.max(0L,
                Duration.between(snapshotTime, LocalDateTime.now()).toMillis());
        AccountView view = new AccountView(String.valueOf(account.getId()), account.getAccountNo(),
                masked(account.getAccountNo()), account.getAccountName(), account.getBroker(), account.getEnvironment(),
                account.getCurrency(), money(account.getTotalAsset()), money(account.getCash()),
                money(account.getMarketValue()), money(account.getProfitLoss()), snapshotTime, true, account.getStatus());
        return new CurrentPortfolioSnapshot(String.valueOf(account.getId()),
                account.getDataVersion() == null ? 0L : account.getDataVersion(), "MYSQL", "OFFLINE", true,
                snapshotTime, ageMs, view, positions,
                List.of("QMT实时快照不可用，当前展示最近一次同步到MySQL的数据。"));
    }

    private List<Account> qmtAccounts() {
        return accountMapper.selectList(Wrappers.<Account>lambdaQuery()
                .eq(Account::getBroker, "GUOJIN_QMT")
                .ne(Account::getStatus, "DISABLED"));
    }

    private boolean isInOfflineBackoff(Long accountId) {
        Long failedAt = automaticFailureAtMs.get(accountId);
        return failedAt != null && System.currentTimeMillis() - failedAt < offlineProbeIntervalMs;
    }

    private Account requireAccount(Long userId, Long accountId) {
        Account account = accountMapper.selectOne(Wrappers.<Account>lambdaQuery()
                .eq(Account::getId, accountId).eq(Account::getUserId, userId));
        if (account == null) {
            throw new BusinessException(404101, "账户不存在", HttpStatus.NOT_FOUND);
        }
        return account;
    }

    private List<PositionView> mapPositions(List<Map<String, Object>> rows) {
        List<PositionView> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            result.add(new PositionView(null, requiredString(row, "symbol"),
                    optionalString(row.get("securityName"), requiredString(row, "symbol")),
                    decimal(requiredDecimal(row, "quantity")), decimal(requiredDecimal(row, "availableQuantity")),
                    decimal(optionalDecimal(row.get("costPrice"))), decimal(optionalDecimal(row.get("lastPrice"))),
                    money(optionalDecimal(row.get("marketValue"))), money(optionalDecimal(row.get("profitLoss"))),
                    optionalString(row.get("industry"), null), optionalString(row.get("region"), null)));
        }
        return List.copyOf(result);
    }

    private PositionView toPositionView(Position position) {
        return new PositionView(String.valueOf(position.getId()), position.getSecurityCode(), position.getSecurityName(),
                decimal(position.getQuantity()), decimal(position.getAvailableQuantity()), decimal(position.getCostPrice()),
                decimal(position.getLastPrice()), money(position.getMarketValue()), money(position.getProfitLoss()),
                position.getIndustry(), position.getRegion());
    }

    private Map<String, Object> requiredMap(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof Map<?, ?> map)) throw contractError("缺少对象字段: " + key);
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((mapKey, mapValue) -> result.put(String.valueOf(mapKey), mapValue));
        return result;
    }

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

    private String requiredString(Map<String, Object> source, String key) {
        String value = optionalString(source.get(key), null);
        if (value == null || value.isBlank()) throw contractError("缺少文本字段: " + key);
        return value;
    }

    private String optionalString(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private BigDecimal requiredDecimal(Map<String, Object> source, String key) {
        if (!source.containsKey(key) || source.get(key) == null) throw contractError("缺少数值字段: " + key);
        return decimalValue(source.get(key), key);
    }

    private BigDecimal optionalDecimal(Object value) {
        return value == null || String.valueOf(value).isBlank() ? BigDecimal.ZERO : decimalValue(value, "optional");
    }

    private BigDecimal decimalValue(Object value, String field) {
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw contractError("非法数值字段: " + field);
        }
    }

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

    private List<String> stringList(Object value) {
        if (!(value instanceof Collection<?> collection)) return List.of();
        return collection.stream().map(String::valueOf).toList();
    }

    private BusinessException contractError(String detail) {
        return new BusinessException(502601, "Python实时组合数据结构错误：" + detail, HttpStatus.BAD_GATEWAY);
    }

    private String decimal(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private String money(BigDecimal value) {
        return value == null ? "0.00" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String masked(String accountNo) {
        return accountNo == null ? null : "****" + accountNo.substring(Math.max(0, accountNo.length() - 4));
    }

    private String safeMessage(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
