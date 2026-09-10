package com.stockmanager.account.history;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stockmanager.account.entity.AccountHistorySnapshot;
import com.stockmanager.account.entity.PositionHistorySnapshot;
import com.stockmanager.account.live.CurrentPortfolioSnapshot;
import com.stockmanager.account.mapper.AccountHistorySnapshotMapper;
import com.stockmanager.account.mapper.PositionHistorySnapshotMapper;
import com.stockmanager.account.vo.AccountView;
import com.stockmanager.account.vo.PositionView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 从已经采集完成的实时快照生成不可变历史。
 *
 * <p>本类永远不调用 QMT；一条资产快照和它对应的完整持仓行在同一个短事务中写入。
 * 持仓不是每个实时 tick 都保存，而是按时间间隔或内容指纹变化保存，控制历史表写入量。</p>
 */
@Service
public class PortfolioHistoryPersistenceService {
    /** 写入账户级资产快照，并查询最近一次携带完整持仓的快照。 */
    private final AccountHistorySnapshotMapper accountSnapshotMapper;
    /** 写入和读取某个账户快照下面的持仓明细。 */
    private final PositionHistorySnapshotMapper positionSnapshotMapper;
    /** 即使持仓内容不变，也必须定期保存一次完整持仓的最长间隔。 */
    private final Duration positionSnapshotInterval;

    /** 注入账户历史和持仓历史 Mapper。 */
    public PortfolioHistoryPersistenceService(AccountHistorySnapshotMapper accountSnapshotMapper,
                                              PositionHistorySnapshotMapper positionSnapshotMapper,
                                              @Value("${app.portfolio-history.position-interval-ms:900000}")
                                              long positionIntervalMs) {
        this.accountSnapshotMapper = accountSnapshotMapper;
        this.positionSnapshotMapper = positionSnapshotMapper;
        this.positionSnapshotInterval = Duration.ofMillis(Math.max(60_000, positionIntervalMs));
    }

    /** 每五分钟保存资产摘要；完整持仓每 15 分钟或检测到内容指纹变化时保存。 */
    @Transactional
    public boolean persistLiveSnapshot(CurrentPortfolioSnapshot snapshot, String snapshotType,
                                       boolean forcePositions) {
        if (snapshot == null || snapshot.stale() || !"LIVE".equals(snapshot.mode())) return false;
        return persistSnapshot(Long.valueOf(snapshot.accountId()), snapshot.snapshotTime(), snapshotType,
                snapshot.source(), String.valueOf(snapshot.dataVersion()), snapshot.account(), snapshot.positions(),
                forcePositions);
    }

    /** 旧服务兼容入口；正常实时链路使用{@link #persistLiveSnapshot}。 */
    @Transactional
    public boolean persistLegacySnapshot(Long accountId, LocalDateTime snapshotTime, String snapshotType,
                                         String source, String dataVersion, AccountView account,
                                         List<PositionView> positions, boolean forcePositions) {
        return persistSnapshot(accountId, snapshotTime, snapshotType, source, dataVersion, account, positions,
                forcePositions);
    }

    private boolean persistSnapshot(Long accountId, LocalDateTime snapshotTime, String snapshotType,
                                      String source, String dataVersion, AccountView account,
                                      List<PositionView> positions, boolean forcePositions) {
        // dataVersion + snapshotType 防止调度重入或重复消费同一 Redis 快照。
        // 缺少关联账户或业务时间的快照无法审计，直接拒绝持久化。
        if (accountId == null || snapshotTime == null) return false;
        String type = blank(snapshotType, "INTRADAY").toUpperCase();
        // 旧链路没有 dataVersion 时用毫秒级业务时间生成稳定版本，便于兼容迁移期数据。
        String version = blank(dataVersion, "legacy-" + millis(snapshotTime));
        // 应用层先做快速幂等查询，避免大多数重复任务触发数据库唯一键异常。
        if (accountSnapshotMapper.selectCount(Wrappers.<AccountHistorySnapshot>lambdaQuery()
                .eq(AccountHistorySnapshot::getAccountId, accountId)
                .eq(AccountHistorySnapshot::getDataVersion, version)
                .eq(AccountHistorySnapshot::getSnapshotType, type)) > 0) {
            return false;
        }

        // 复制列表，避免事务执行期间调用方异步修改原集合。
        List<PositionView> immutablePositions = positions == null ? List.of() : List.copyOf(positions);
        LocalDateTime persistedTime = millis(snapshotTime);
        // 资产每次可保存，但持仓只有到期或指纹变化才保存，避免 2 秒采集频率直接写爆历史表。
        boolean capturePositions = forcePositions || positionsChangedOrDue(accountId, persistedTime, immutablePositions);
        LocalDateTime now = LocalDateTime.now();

        // 账户资产摘要每个采样周期都可保存，positionsCaptured标记本行是否附带完整持仓子表。
        AccountHistorySnapshot history = new AccountHistorySnapshot();
        history.setAccountId(accountId);
        history.setSnapshotTime(persistedTime);
        history.setSnapshotType(type);
        history.setTotalAsset(decimal(account == null ? null : account.totalAsset()));
        history.setCash(decimal(account == null ? null : account.cash()));
        history.setMarketValue(decimal(account == null ? null : account.marketValue()));
        history.setProfitLoss(decimal(account == null ? null : account.profitLoss()));
        history.setPositionCount(immutablePositions.size());
        history.setPositionsCaptured(capturePositions);
        history.setSource(blank(source, "QMT").toUpperCase());
        history.setDataVersion(version);
        history.setSourceRecordId(null);
        history.setCreatedAt(now);
        try {
            // insert 成功后 MyBatis-Plus 会回填主键，持仓子表用该主键建立快照归属。
            accountSnapshotMapper.insert(history);
        } catch (DuplicateKeyException ignored) {
            // 并发实例同时通过前置查询时由数据库唯一键兜底，重复采样视为未写入而不是系统错误。
            return false;
        }

        if (capturePositions) {
            // 先插入账户快照拿到 snapshotId，再把每一行持仓挂到这个不可变快照上。
            for (PositionView view : immutablePositions) {
                if (view.symbol() == null || view.symbol().isBlank()) continue;
                PositionHistorySnapshot row = new PositionHistorySnapshot();
                row.setSnapshotId(history.getId());
                row.setAccountId(accountId);
                row.setSnapshotTime(persistedTime);
                row.setSecurityCode(view.symbol());
                row.setSecurityName(view.securityName());
                row.setQuantity(decimal(view.quantity()));
                row.setAvailableQuantity(decimal(view.availableQuantity()));
                row.setCostPrice(decimal(view.costPrice()));
                row.setLastPrice(decimal(view.lastPrice()));
                row.setMarketValue(decimal(view.marketValue()));
                row.setProfitLoss(decimal(view.profitLoss()));
                row.setIndustry(view.industry());
                row.setRegion(view.region());
                row.setCreatedAt(now);
                positionSnapshotMapper.insert(row);
            }
        }
        return true;
    }

    /**
     * 决定本次是否需要保存完整持仓。
     * 首次采样、距离上次完整采样已到配置间隔、或内容指纹发生变化时返回 true。
     */
    private boolean positionsChangedOrDue(Long accountId, LocalDateTime now, List<PositionView> current) {
        // 指纹按证券代码排序，确保 QMT 返回顺序变化不会被误判为持仓内容变化。
        AccountHistorySnapshot previous = accountSnapshotMapper.findLatestWithPositionsAtOrBefore(accountId, now);
        if (previous == null || previous.getSnapshotTime() == null
                || Duration.between(previous.getSnapshotTime(), now).compareTo(positionSnapshotInterval) >= 0) {
            return true;
        }
        // 尚未到固定间隔时，通过内容指纹判断数量、价格、市值、行业等是否变化。
        List<PositionHistorySnapshot> stored = positionSnapshotMapper.selectList(
                Wrappers.<PositionHistorySnapshot>lambdaQuery()
                        .eq(PositionHistorySnapshot::getSnapshotId, previous.getId()));
        return !fingerprint(current).equals(fingerprintStored(stored));
    }

    /** 生成当前 PositionView 集合的顺序无关内容指纹，用于判断业务字段是否变化。 */
    private String fingerprint(List<PositionView> positions) {
        // 统一十进制文本并按代码排序，避免集合顺序和BigDecimal尾零造成误判。
        return positions.stream().filter(Objects::nonNull)
                .sorted(Comparator.comparing(view -> blank(view.symbol(), "")))
                .map(view -> String.join("|", blank(view.symbol(), ""), blank(view.quantity(), "0"),
                        blank(view.availableQuantity(), "0"), blank(view.costPrice(), "0"),
                        blank(view.lastPrice(), "0"), blank(view.marketValue(), "0"),
                        blank(view.profitLoss(), "0"), blank(view.industry(), ""), blank(view.region(), "")))
                .reduce("", (left, right) -> left + '\n' + right);
    }

    /** 以与当前视图完全相同的规则生成数据库持仓快照指纹，保证两侧可以直接比较。 */
    private String fingerprintStored(List<PositionHistorySnapshot> positions) {
        return positions.stream().filter(Objects::nonNull)
                .sorted(Comparator.comparing(view -> blank(view.getSecurityCode(), "")))
                .map(view -> String.join("|", blank(view.getSecurityCode(), ""), decimalText(view.getQuantity()),
                        decimalText(view.getAvailableQuantity()), decimalText(view.getCostPrice()),
                        decimalText(view.getLastPrice()), decimalText(view.getMarketValue()),
                        decimalText(view.getProfitLoss()), blank(view.getIndustry(), ""), blank(view.getRegion(), "")))
                .reduce("", (left, right) -> left + '\n' + right);
    }

    /** 将前后端协议中的十进制字符串恢复为 BigDecimal；迁移期非法旧值按零兼容。 */
    private BigDecimal decimal(String value) {
        // 视图层金融字段以字符串传输；持久化前恢复BigDecimal，非法旧值按0兼容。
        try { return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value); }
        catch (NumberFormatException ignored) { return BigDecimal.ZERO; }
    }

    /** BigDecimal 指纹文本去除尾零，确保 1、1.0、1.00 被视为相同数值。 */
    private String decimalText(BigDecimal value) { return value == null ? "0" : value.stripTrailingZeros().toPlainString(); }
    /** 返回非空文本，统一处理指纹和元数据的缺省值。 */
    private String blank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    /** 将时间截断到 MySQL DATETIME(3) 精度，避免纳秒差异破坏唯一键和查询匹配。 */
    private LocalDateTime millis(LocalDateTime value) { return value.withNano(value.getNano() / 1_000_000 * 1_000_000); }
}
