package com.stockmanager.account.live;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.stockmanager.account.entity.Account;
import com.stockmanager.account.entity.Position;
import com.stockmanager.account.mapper.AccountMapper;
import com.stockmanager.account.mapper.PositionMapper;
import com.stockmanager.account.vo.AccountView;
import com.stockmanager.account.vo.PositionView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** Keeps the write transaction short: QMT is read before this service is called. */
@Service
public class PortfolioLivePersistenceService {
    private final AccountMapper accountMapper;
    private final PositionMapper positionMapper;

    public PortfolioLivePersistenceService(AccountMapper accountMapper, PositionMapper positionMapper) {
        this.accountMapper = accountMapper;
        this.positionMapper = positionMapper;
    }

    @Transactional
    public void persistCurrentState(Long accountId, CurrentPortfolioSnapshot snapshot) {
        if (!"LIVE".equals(snapshot.mode()) || snapshot.stale()) return;
        Account account = accountMapper.selectById(accountId);
        if (account == null) return;
        AccountView live = snapshot.account();
        account.setAccountNo(orDefault(live.externalAccountId(), account.getAccountNo()));
        account.setAccountName(orDefault(live.accountName(), account.getAccountName()));
        account.setBroker(orDefault(live.broker(), account.getBroker()));
        account.setEnvironment(orDefault(live.environment(), account.getEnvironment()));
        account.setCurrency(orDefault(live.currency(), account.getCurrency()));
        account.setTotalAsset(decimal(live.totalAsset()));
        account.setCash(decimal(live.cash()));
        account.setMarketValue(decimal(live.marketValue()));
        account.setProfitLoss(decimal(live.profitLoss()));
        account.setLastSyncTime(snapshot.snapshotTime());
        account.setDataVersion(snapshot.dataVersion());
        account.setStatus("ACTIVE");
        accountMapper.updateById(account);

        positionMapper.delete(Wrappers.<Position>lambdaQuery().eq(Position::getAccountId, accountId));
        java.util.List<Position> positions = new java.util.ArrayList<>();
        for (PositionView view : snapshot.positions()) {
            Position position = new Position();
            position.setId(IdWorker.getId());
            position.setAccountId(accountId);
            position.setSecurityCode(view.symbol());
            position.setSecurityName(view.securityName());
            position.setQuantity(decimal(view.quantity()));
            position.setAvailableQuantity(decimal(view.availableQuantity()));
            position.setCostPrice(decimal(view.costPrice()));
            position.setLastPrice(decimal(view.lastPrice()));
            position.setMarketValue(decimal(view.marketValue()));
            position.setProfitLoss(decimal(view.profitLoss()));
            position.setIndustry(view.industry());
            position.setRegion(view.region());
            positions.add(position);
        }
        if (!positions.isEmpty()) {
            positionMapper.insertBatch(positions);
        }
    }

    private BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    private String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
