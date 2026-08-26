package com.stockmanager.account.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stockmanager.account.entity.AccountHistorySnapshot;
import com.stockmanager.account.entity.Account;
import com.stockmanager.account.entity.Position;
import com.stockmanager.account.history.PortfolioHistoryPersistenceService;
import com.stockmanager.account.mapper.AccountMapper;
import com.stockmanager.account.mapper.AccountHistorySnapshotMapper;
import com.stockmanager.account.mapper.PositionMapper;
import com.stockmanager.account.service.AccountService;
import com.stockmanager.account.vo.AccountSyncView;
import com.stockmanager.account.vo.AccountView;
import com.stockmanager.account.vo.PositionView;
import com.stockmanager.common.exception.BusinessException;
import com.stockmanager.integration.quant.QuantClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AccountServiceImpl implements AccountService {
    private final AccountMapper accountMapper;
    private final PositionMapper positionMapper;
    private final AccountHistorySnapshotMapper historySnapshotMapper;
    private final PortfolioHistoryPersistenceService historyPersistenceService;
    private final QuantClient quantClient;

    public AccountServiceImpl(AccountMapper accountMapper, PositionMapper positionMapper,
                              AccountHistorySnapshotMapper historySnapshotMapper,
                              PortfolioHistoryPersistenceService historyPersistenceService,
                              QuantClient quantClient) {
        this.accountMapper = accountMapper;
        this.positionMapper = positionMapper;
        this.historySnapshotMapper = historySnapshotMapper;
        this.historyPersistenceService = historyPersistenceService;
        this.quantClient = quantClient;
    }

    @Override
    public List<AccountView> listAccounts(Long userId) {
        return accountMapper.selectList(Wrappers.<Account>lambdaQuery().eq(Account::getUserId, userId))
                .stream().map(this::toView).toList();
    }

    @Override
    public AccountView getAccount(Long userId, Long accountId) {
        return toView(requireAccount(userId, accountId));
    }

    @Override
    public List<PositionView> listPositions(Long userId, Long accountId) {
        requireAccount(userId, accountId);
        return positionMapper.selectList(Wrappers.<Position>lambdaQuery().eq(Position::getAccountId, accountId))
                .stream().map(this::toPositionView).toList();
    }

    @Override
    @Transactional
    public AccountSyncView syncAccount(Long userId, Long accountId, String traceId) {
        return syncAccountInternal(userId, accountId, traceId, "MANUAL", true);
    }

    private AccountSyncView syncAccountInternal(Long userId, Long accountId, String traceId,
                                                String snapshotType, boolean forceSnapshot) {
        Account account = requireAccount(userId, accountId);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("accountId", String.valueOf(accountId));
        request.put("externalAccountId", account.getAccountNo());
        request.put("environment", account.getEnvironment());
        request.put("includePositions", true);
        request.put("includeOrders", false);

        Map<String, Object> result = quantClient.syncAccount(request, traceId);
        Map<String, Object> asset = requiredMap(result, "account");
        LocalDateTime snapshotTime = parseDateTime(requiredString(result, "snapshotTime"));
        String source = optionalString(result.get("source"), "unknown");
        boolean qmtSource = "qmt".equalsIgnoreCase(source);

        account.setAccountNo(optionalString(asset.get("externalAccountId"), account.getAccountNo()));
        account.setAccountName(optionalString(asset.get("accountName"),
                qmtSource ? "国金QMT模拟账户" : account.getAccountName()));
        account.setBroker(optionalString(asset.get("broker"),
                qmtSource ? "GUOJIN_QMT" : account.getBroker()));
        account.setEnvironment(optionalString(asset.get("environment"), "SIMULATION"));
        account.setCurrency(optionalString(asset.get("currency"), "CNY"));
        account.setTotalAsset(requiredDecimal(asset, "totalAsset"));
        account.setCash(requiredDecimal(asset, "cash"));
        account.setMarketValue(requiredDecimal(asset, "marketValue"));
        account.setProfitLoss(optionalDecimal(asset.get("profitLoss"), BigDecimal.ZERO));
        account.setLastSyncTime(snapshotTime);
        account.setStatus("ACTIVE");
        accountMapper.updateById(account);

        positionMapper.delete(Wrappers.<Position>lambdaQuery().eq(Position::getAccountId, accountId));
        List<PositionView> positions = new ArrayList<>();
        for (Map<String, Object> item : mapList(result.get("positions"), "positions")) {
            Position position = new Position();
            position.setAccountId(accountId);
            position.setSecurityCode(requiredString(item, "symbol"));
            position.setSecurityName(optionalString(item.get("securityName"), position.getSecurityCode()));
            position.setQuantity(requiredDecimal(item, "quantity"));
            position.setAvailableQuantity(requiredDecimal(item, "availableQuantity"));
            position.setCostPrice(optionalDecimal(item.get("costPrice"), BigDecimal.ZERO));
            position.setLastPrice(optionalDecimal(item.get("lastPrice"), BigDecimal.ZERO));
            position.setMarketValue(optionalDecimal(item.get("marketValue"), BigDecimal.ZERO));
            position.setProfitLoss(optionalDecimal(item.get("profitLoss"), BigDecimal.ZERO));
            position.setIndustry(optionalString(item.get("industry"), null));
            position.setRegion(optionalString(item.get("region"), null));
            positionMapper.insert(position);
            positions.add(toPositionView(position));
        }

        List<String> warnings = stringList(result.get("warnings"));
        try {
            historyPersistenceService.persistLegacySnapshot(accountId, snapshotTime, snapshotType, source,
                    "legacy-" + snapshotTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    toView(account), positions, forceSnapshot);
        } catch (Exception ex) {
            warnings.add("MySQL已同步，但历史快照保存失败");
        }

        return new AccountSyncView(toView(account), positions,
                source, snapshotTime, List.copyOf(warnings));
    }

    @Override
    public List<AccountHistorySnapshot> listSnapshots(Long userId, Long accountId, LocalDate from, LocalDate to) {
        requireAccount(userId, accountId);
        LocalDate start = from == null ? LocalDate.now().minusDays(365) : from;
        LocalDate end = to == null ? LocalDate.now() : to;
        return historySnapshotMapper.selectList(Wrappers.<AccountHistorySnapshot>lambdaQuery()
                .eq(AccountHistorySnapshot::getAccountId, accountId)
                .between(AccountHistorySnapshot::getSnapshotTime, start.atStartOfDay(),
                        end.plusDays(1).atStartOfDay().minusNanos(1))
                .orderByAsc(AccountHistorySnapshot::getSnapshotTime)
                .orderByAsc(AccountHistorySnapshot::getId));
    }

    @Override
    public Map<String, Object> qmtStatus(String traceId) {
        return quantClient.health(traceId);
    }

    private Account requireAccount(Long userId, Long accountId) {
        Account account = accountMapper.selectOne(Wrappers.<Account>lambdaQuery()
                .eq(Account::getId, accountId).eq(Account::getUserId, userId));
        if (account == null) {
            throw new BusinessException(404101, "账户不存在", HttpStatus.NOT_FOUND);
        }
        return account;
    }

    private AccountView toView(Account account) {
        boolean stale = account.getLastSyncTime() == null
                || Duration.between(account.getLastSyncTime(), LocalDateTime.now()).toMinutes() > 5;
        String masked = account.getAccountNo() == null ? null
                : "****" + account.getAccountNo().substring(Math.max(0, account.getAccountNo().length() - 4));
        return new AccountView(String.valueOf(account.getId()), account.getAccountNo(), masked, account.getAccountName(),
                account.getBroker(), account.getEnvironment(), account.getCurrency(), money(account.getTotalAsset()),
                money(account.getCash()), money(account.getMarketValue()), money(account.getProfitLoss()),
                account.getLastSyncTime(), stale, account.getStatus());
    }

    private PositionView toPositionView(Position position) {
        return new PositionView(String.valueOf(position.getId()), position.getSecurityCode(),
                position.getSecurityName(), decimal(position.getQuantity()), decimal(position.getAvailableQuantity()),
                decimal(position.getCostPrice()), decimal(position.getLastPrice()), money(position.getMarketValue()),
                money(position.getProfitLoss()), position.getIndustry(), position.getRegion());
    }

    private Map<String, Object> requiredMap(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof Map<?, ?> map)) {
            throw contractError("缺少对象字段: " + key);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((mapKey, mapValue) -> result.put(String.valueOf(mapKey), mapValue));
        return result;
    }

    private List<Map<String, Object>> mapList(Object value, String field) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof Collection<?> collection)) {
            throw contractError("字段不是数组: " + field);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : collection) {
            if (!(item instanceof Map<?, ?> map)) {
                throw contractError("数组元素不是对象: " + field);
            }
            Map<String, Object> converted = new LinkedHashMap<>();
            map.forEach((mapKey, mapValue) -> converted.put(String.valueOf(mapKey), mapValue));
            result.add(converted);
        }
        return result;
    }

    private String requiredString(Map<String, Object> source, String key) {
        String value = optionalString(source.get(key), null);
        if (value == null || value.isBlank()) {
            throw contractError("缺少文本字段: " + key);
        }
        return value;
    }

    private String optionalString(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private BigDecimal requiredDecimal(Map<String, Object> source, String key) {
        if (!source.containsKey(key) || source.get(key) == null) {
            throw contractError("缺少数值字段: " + key);
        }
        return decimalValue(source.get(key), key);
    }

    private BigDecimal optionalDecimal(Object value, BigDecimal fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : decimalValue(value, "optional");
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
        List<String> result = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> result.add(String.valueOf(item)));
        }
        return result;
    }

    private BusinessException contractError(String detail) {
        return new BusinessException(502601, "Python账户同步数据结构错误：" + detail, HttpStatus.BAD_GATEWAY);
    }

    private String money(BigDecimal value) {
        return value == null ? null : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
