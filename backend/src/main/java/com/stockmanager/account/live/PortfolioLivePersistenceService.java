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

/**
 * 实时组合当前表写入器。
 *
 * <p>调用本类之前 QMT 已经读取完成，因此事务只包含 MySQL 的更新，不会把外部网络等待
 * 放进事务。账户行和持仓行必须在同一个事务中更新，页面降级读取时才能看到同一版本的数据。</p>
 */
@Service
public class PortfolioLivePersistenceService {
    /** 更新账户当前资产摘要和同步版本。 */
    private final AccountMapper accountMapper;
    /** 以快照替换方式维护账户当前持仓集合。 */
    private final PositionMapper positionMapper;

    /** 注入账户和持仓 Mapper。 */
    public PortfolioLivePersistenceService(AccountMapper accountMapper, PositionMapper positionMapper) {
        this.accountMapper = accountMapper;
        this.positionMapper = positionMapper;
    }

    /**
     * 在短事务中把 Redis/QMT 实时快照更新为 MySQL 当前账户和全量持仓事实。
     */
    @Transactional
    public void persistCurrentState(Long accountId, CurrentPortfolioSnapshot snapshot) {
        // 只接受 LIVE 且非 stale 快照；离线数据不能反向覆盖当前账户的最后一次有效实时状态。
        if (!"LIVE".equals(snapshot.mode()) || snapshot.stale()) return;
        // 重新查询受管账户实体，避免使用采集开始前的旧对象覆盖并发更新字段。
        Account account = accountMapper.selectById(accountId);
        if (account == null) return;
        // snapshot.account 与 snapshot.positions 来自同一次 QMT 响应，因此必须在同一事务落库。
        AccountView live = snapshot.account();
        // QMT 未返回的静态元数据沿用原值；资产数值则以本次实时事实为准。
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
        // 先更新账户头信息，随后替换持仓；事务提交前外部读者不会看到半完成状态。
        accountMapper.updateById(account);

        // 当前持仓采用“同事务删除旧集合 + 批量插入新集合”，实现快照替换而不是逐行猜测差异。
        positionMapper.delete(Wrappers.<Position>lambdaQuery().eq(Position::getAccountId, accountId));
        java.util.List<Position> positions = new java.util.ArrayList<>();
        for (PositionView view : snapshot.positions()) {
            // 为每行持仓生成新 Snowflake ID；历史关联使用独立历史表，不依赖当前表主键稳定。
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
            // 使用 Mapper 批量 SQL 降低逐条 insert 的网络往返和事务日志开销。
            positionMapper.insertBatch(positions);
        }
    }

    /** 将内部协议的十进制字符串安全恢复成 BigDecimal；缺失金融值按零保存。 */
    private BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    /** QMT 文本字段缺失时保留 MySQL 原值，避免一次不完整响应擦除账户元数据。 */
    private String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
