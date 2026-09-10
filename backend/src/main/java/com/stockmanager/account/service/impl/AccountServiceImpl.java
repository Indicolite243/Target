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

/**
 * 账户应用服务实现，负责账户查询、持仓查询、QMT 手动同步以及历史快照读取。
 *
 * <p>该类位于 HTTP Controller 与外部量化服务之间，主要承担三类工作：</p>
 * <ul>
 *     <li>校验当前用户是否拥有目标账户，防止跨用户读取或同步；</li>
 *     <li>把 FastAPI/QMT 返回的弱类型 Map 严格转换为账户和持仓实体；</li>
 *     <li>在当前表更新成功后尽力保存历史快照，并把非关键失败降级为 warning。</li>
 * </ul>
 *
 * <p>当前账户和持仓是 MySQL 中的最新事实副本。实时页面优先读取 Redis 快照；本类的同步接口
 * 用于用户显式要求刷新或其他兼容场景，不能被高频轮询反复调用。</p>
 */
@Service
public class AccountServiceImpl implements AccountService {
    /** 当前账户主表访问入口。 */
    private final AccountMapper accountMapper;
    /** 当前持仓表访问入口。 */
    private final PositionMapper positionMapper;
    /** 历史资产快照查询入口。 */
    private final AccountHistorySnapshotMapper historySnapshotMapper;
    /** 把当前账户与持仓转换为不可变历史快照的事务边界。 */
    private final PortfolioHistoryPersistenceService historyPersistenceService;
    /** Java 调用 FastAPI/QMT 的统一 HTTP 客户端。 */
    private final QuantClient quantClient;

    /** 注入当前账户、持仓、历史快照和量化服务访问依赖。 */
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

    /**
     * 查询用户拥有的全部账户摘要。
     *
     * @param userId 当前登录用户 ID
     * @return 只包含该用户账户的展示模型
     */
    @Override
    public List<AccountView> listAccounts(Long userId) {
        // userId 必须进入 SQL 条件，不能查全表后再在内存中过滤。
        return accountMapper.selectList(Wrappers.<Account>lambdaQuery().eq(Account::getUserId, userId))
                .stream().map(this::toView).toList();
    }

    /**
     * 查询单个账户摘要，并在查询条件中同时限制账户 ID 与用户 ID。
     */
    @Override
    public AccountView getAccount(Long userId, Long accountId) {
        return toView(requireAccount(userId, accountId));
    }

    /**
     * 查询账户当前持仓。先校验账户归属，再按账户 ID 读取持仓，避免越权枚举。
     */
    @Override
    public List<PositionView> listPositions(Long userId, Long accountId) {
        // 先校验账户归属；Position 表自身没有 user_id，不能只凭 accountId 判断访问权限。
        requireAccount(userId, accountId);
        return positionMapper.selectList(Wrappers.<Position>lambdaQuery().eq(Position::getAccountId, accountId))
                .stream().map(this::toPositionView).toList();
    }

    /**
     * 执行一次用户触发的账户同步。
     *
     * <p>方法事务覆盖“账户主表 + 当前持仓替换”；QMT 返回的任一必需字段不符合契约时，
     * 整个当前数据更新会回滚，避免账户资产与持仓来自两次不同步的结果。</p>
     */
    @Override
    @Transactional
    public AccountSyncView syncAccount(Long userId, Long accountId, String traceId) {
        return syncAccountInternal(userId, accountId, traceId, "MANUAL", true);
    }

    /**
     * 账户同步核心流程。
     *
     * @param snapshotType 写入历史表时使用的快照类型，例如 MANUAL
     * @param forceSnapshot 是否忽略历史采样间隔并强制保存本次完整持仓
     */
    private AccountSyncView syncAccountInternal(Long userId, Long accountId, String traceId,
                                                String snapshotType, boolean forceSnapshot) {
        // 先做资源归属校验，再把内部账户 ID 与券商资金账号一并发送给量化服务。
        Account account = requireAccount(userId, accountId);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("accountId", String.valueOf(accountId));
        request.put("externalAccountId", account.getAccountNo());
        request.put("environment", account.getEnvironment());
        request.put("includePositions", true);
        request.put("includeOrders", false);

        // FastAPI 返回 Map 是跨语言边界；必需字段使用 required* 读取，禁止静默填零掩盖协议错误。
        Map<String, Object> result = quantClient.syncAccount(request, traceId);
        Map<String, Object> asset = requiredMap(result, "account");
        LocalDateTime snapshotTime = parseDateTime(requiredString(result, "snapshotTime"));
        String source = optionalString(result.get("source"), "unknown");
        boolean qmtSource = "qmt".equalsIgnoreCase(source);

        // 账户资产先更新，持仓随后在同一事务中整体替换，保证两张当前表的数据版本一致。
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

        // 当前持仓采用“删除旧集合 + 插入本次完整集合”，因为 QMT 返回的是全量快照而非增量事件。
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

        // 历史快照用于风险、时间对比和归因。其失败不应回滚已经成功的当前账户同步。
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

    /**
     * 读取指定日期范围内的账户资产历史，并保持时间与 ID 的稳定升序。
     */
    @Override
    public List<AccountHistorySnapshot> listSnapshots(Long userId, Long accountId, LocalDate from, LocalDate to) {
        requireAccount(userId, accountId);
        // 前端未传区间时默认最近一年；结束日扩展到当天最后一纳秒，覆盖整日记录。
        LocalDate start = from == null ? LocalDate.now().minusDays(365) : from;
        LocalDate end = to == null ? LocalDate.now() : to;
        return historySnapshotMapper.selectList(Wrappers.<AccountHistorySnapshot>lambdaQuery()
                .eq(AccountHistorySnapshot::getAccountId, accountId)
                .between(AccountHistorySnapshot::getSnapshotTime, start.atStartOfDay(),
                        end.plusDays(1).atStartOfDay().minusNanos(1))
                .orderByAsc(AccountHistorySnapshot::getSnapshotTime)
                .orderByAsc(AccountHistorySnapshot::getId));
    }

    /**
     * 透传量化服务健康状态，供前端判断 QMT 是否连接以及模拟交易是否开启。
     */
    @Override
    public Map<String, Object> qmtStatus(String traceId) {
        return quantClient.health(traceId);
    }

    /** 按“账户 ID + 用户 ID”读取账户；不存在或不属于当前用户时统一返回 404。 */
    private Account requireAccount(Long userId, Long accountId) {
        Account account = accountMapper.selectOne(Wrappers.<Account>lambdaQuery()
                .eq(Account::getId, accountId).eq(Account::getUserId, userId));
        if (account == null) {
            throw new BusinessException(404101, "账户不存在", HttpStatus.NOT_FOUND);
        }
        return account;
    }

    /**
     * 把账户实体转换为 API 模型，同时生成脱敏资金账号和数据是否陈旧的标记。
     */
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

    /** 把持仓实体转换为字符串数值模型，避免金额经 JavaScript Number 产生精度误差。 */
    private PositionView toPositionView(Position position) {
        return new PositionView(String.valueOf(position.getId()), position.getSecurityCode(),
                position.getSecurityName(), decimal(position.getQuantity()), decimal(position.getAvailableQuantity()),
                decimal(position.getCostPrice()), decimal(position.getLastPrice()), money(position.getMarketValue()),
                money(position.getProfitLoss()), position.getIndustry(), position.getRegion());
    }

    /** 从跨语言响应中读取必需对象字段，并把任意 Map 键统一转换为字符串。 */
    private Map<String, Object> requiredMap(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof Map<?, ?> map)) {
            throw contractError("缺少对象字段: " + key);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((mapKey, mapValue) -> result.put(String.valueOf(mapKey), mapValue));
        return result;
    }

    /** 从跨语言响应中安全读取对象数组；类型不符时按上游契约错误处理。 */
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

    /** 读取非空必需文本字段。 */
    private String requiredString(Map<String, Object> source, String key) {
        String value = optionalString(source.get(key), null);
        if (value == null || value.isBlank()) {
            throw contractError("缺少文本字段: " + key);
        }
        return value;
    }

    /** 读取可选文本；null 或空白值使用调用方提供的默认值。 */
    private String optionalString(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    /** 读取必需高精度数值字段，缺失值不能默认为零。 */
    private BigDecimal requiredDecimal(Map<String, Object> source, String key) {
        if (!source.containsKey(key) || source.get(key) == null) {
            throw contractError("缺少数值字段: " + key);
        }
        return decimalValue(source.get(key), key);
    }

    /** 读取可选高精度数值；缺失时返回业务默认值。 */
    private BigDecimal optionalDecimal(Object value, BigDecimal fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : decimalValue(value, "optional");
    }

    /** 使用字符串中转构造 BigDecimal，兼容 JSON 数字和 JSON 字符串两种表示。 */
    private BigDecimal decimalValue(Object value, String field) {
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw contractError("非法数值字段: " + field);
        }
    }

    /** 兼容带时区与不带时区的 ISO 时间格式，并统一转为本地时间。 */
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

    /** 把可选集合转成可追加的警告文本列表。 */
    private List<String> stringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> result.add(String.valueOf(item)));
        }
        return result;
    }

    /** 构造统一的 502 上游数据契约异常。 */
    private BusinessException contractError(String detail) {
        return new BusinessException(502601, "Python账户同步数据结构错误：" + detail, HttpStatus.BAD_GATEWAY);
    }

    /** 账户金额固定保留两位小数输出。 */
    private String money(BigDecimal value) {
        return value == null ? null : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /** 数量和价格移除无意义尾零后输出。 */
    private String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
