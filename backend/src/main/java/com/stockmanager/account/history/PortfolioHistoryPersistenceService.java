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
 * Persists immutable history from an already collected portfolio snapshot.
 * It never calls QMT and keeps all writes in one short MySQL transaction.
 */
@Service
public class PortfolioHistoryPersistenceService {
    private final AccountHistorySnapshotMapper accountSnapshotMapper;
    private final PositionHistorySnapshotMapper positionSnapshotMapper;
    private final Duration positionSnapshotInterval;

    public PortfolioHistoryPersistenceService(AccountHistorySnapshotMapper accountSnapshotMapper,
                                              PositionHistorySnapshotMapper positionSnapshotMapper,
                                              @Value("${app.portfolio-history.position-interval-ms:900000}")
                                              long positionIntervalMs) {
        this.accountSnapshotMapper = accountSnapshotMapper;
        this.positionSnapshotMapper = positionSnapshotMapper;
        this.positionSnapshotInterval = Duration.ofMillis(Math.max(60_000, positionIntervalMs));
    }

    /** Five-minute asset history entry; full positions are captured every 15 minutes or when changed. */
    @Transactional
    public boolean persistLiveSnapshot(CurrentPortfolioSnapshot snapshot, String snapshotType,
                                       boolean forcePositions) {
        if (snapshot == null || snapshot.stale() || !"LIVE".equals(snapshot.mode())) return false;
        return persistSnapshot(Long.valueOf(snapshot.accountId()), snapshot.snapshotTime(), snapshotType,
                snapshot.source(), String.valueOf(snapshot.dataVersion()), snapshot.account(), snapshot.positions(),
                forcePositions);
    }

    /** Compatibility path for the old service. Normal traffic uses {@link #persistLiveSnapshot}. */
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
        if (accountId == null || snapshotTime == null) return false;
        String type = blank(snapshotType, "INTRADAY").toUpperCase();
        String version = blank(dataVersion, "legacy-" + millis(snapshotTime));
        if (accountSnapshotMapper.selectCount(Wrappers.<AccountHistorySnapshot>lambdaQuery()
                .eq(AccountHistorySnapshot::getAccountId, accountId)
                .eq(AccountHistorySnapshot::getDataVersion, version)
                .eq(AccountHistorySnapshot::getSnapshotType, type)) > 0) {
            return false;
        }

        List<PositionView> immutablePositions = positions == null ? List.of() : List.copyOf(positions);
        LocalDateTime persistedTime = millis(snapshotTime);
        boolean capturePositions = forcePositions || positionsChangedOrDue(accountId, persistedTime, immutablePositions);
        LocalDateTime now = LocalDateTime.now();

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
            accountSnapshotMapper.insert(history);
        } catch (DuplicateKeyException ignored) {
            return false;
        }

        if (capturePositions) {
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

    private boolean positionsChangedOrDue(Long accountId, LocalDateTime now, List<PositionView> current) {
        AccountHistorySnapshot previous = accountSnapshotMapper.findLatestWithPositionsAtOrBefore(accountId, now);
        if (previous == null || previous.getSnapshotTime() == null
                || Duration.between(previous.getSnapshotTime(), now).compareTo(positionSnapshotInterval) >= 0) {
            return true;
        }
        List<PositionHistorySnapshot> stored = positionSnapshotMapper.selectList(
                Wrappers.<PositionHistorySnapshot>lambdaQuery()
                        .eq(PositionHistorySnapshot::getSnapshotId, previous.getId()));
        return !fingerprint(current).equals(fingerprintStored(stored));
    }

    private String fingerprint(List<PositionView> positions) {
        return positions.stream().filter(Objects::nonNull)
                .sorted(Comparator.comparing(view -> blank(view.symbol(), "")))
                .map(view -> String.join("|", blank(view.symbol(), ""), blank(view.quantity(), "0"),
                        blank(view.availableQuantity(), "0"), blank(view.costPrice(), "0"),
                        blank(view.lastPrice(), "0"), blank(view.marketValue(), "0"),
                        blank(view.profitLoss(), "0"), blank(view.industry(), ""), blank(view.region(), "")))
                .reduce("", (left, right) -> left + '\n' + right);
    }

    private String fingerprintStored(List<PositionHistorySnapshot> positions) {
        return positions.stream().filter(Objects::nonNull)
                .sorted(Comparator.comparing(view -> blank(view.getSecurityCode(), "")))
                .map(view -> String.join("|", blank(view.getSecurityCode(), ""), decimalText(view.getQuantity()),
                        decimalText(view.getAvailableQuantity()), decimalText(view.getCostPrice()),
                        decimalText(view.getLastPrice()), decimalText(view.getMarketValue()),
                        decimalText(view.getProfitLoss()), blank(view.getIndustry(), ""), blank(view.getRegion(), "")))
                .reduce("", (left, right) -> left + '\n' + right);
    }

    private BigDecimal decimal(String value) {
        try { return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value); }
        catch (NumberFormatException ignored) { return BigDecimal.ZERO; }
    }

    private String decimalText(BigDecimal value) { return value == null ? "0" : value.stripTrailingZeros().toPlainString(); }
    private String blank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private LocalDateTime millis(LocalDateTime value) { return value.withNano(value.getNano() / 1_000_000 * 1_000_000); }
}
