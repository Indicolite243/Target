package com.stockmanager.account.service.impl;

import com.stockmanager.account.entity.Account;
import com.stockmanager.account.entity.Position;
import com.stockmanager.account.mapper.AccountMapper;
import com.stockmanager.account.mapper.PositionMapper;
import com.stockmanager.account.service.AccountProvisioningService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 新用户默认模拟账户初始化实现。
 *
 * <p>该服务只创建项目内置的教学模拟数据，不连接 QMT，也不会产生任何券商委托。
 * 调用方注册服务负责提供事务，因此用户、账户和初始持仓会作为一个整体提交或回滚。</p>
 */
@Service
public class AccountProvisioningServiceImpl implements AccountProvisioningService {
    // 账户表数据访问接口，用于插入默认模拟账户。
    private final AccountMapper accountMapper;
    // 持仓表数据访问接口，用于插入默认示例持仓。
    private final PositionMapper positionMapper;

    /** 注入账户与持仓数据访问接口。 */
    public AccountProvisioningServiceImpl(AccountMapper accountMapper, PositionMapper positionMapper) {
        // 保存账户 Mapper 依赖。
        this.accountMapper = accountMapper;
        // 保存持仓 Mapper 依赖。
        this.positionMapper = positionMapper;
    }

    /**
     * 为新用户创建一百万元初始资产的模拟账户，并插入一条示例持仓。
     *
     * @param userId 已落库的新用户 ID
     * @param username 用于生成账户展示名称的用户名
     */
    @Override
    public void createSimulationAccount(Long userId, String username) {
        // 账户编号仅用于项目内模拟模式，不复用或伪造真实券商资金账号。
        Account account = new Account();
        // 记录账户所属用户。
        account.setUserId(userId);
        // 使用用户 ID 生成不会与真实券商混淆的模拟账户编号。
        account.setAccountNo("SIM-" + userId);
        // 使用用户名生成页面展示名称。
        account.setAccountName(username + "的模拟账户");
        // 标记数据由本地模拟器产生。
        account.setBroker("SIMULATOR");
        // 标记账户运行在模拟环境。
        account.setEnvironment("SIMULATION");
        // 设置账户资金币种为人民币。
        account.setCurrency("CNY");
        // 设置示例账户初始总资产。
        account.setTotalAsset(new BigDecimal("1000000.00"));
        // 设置示例账户初始可用现金。
        account.setCash(new BigDecimal("902200.00"));
        // 设置初始持仓市值。
        account.setMarketValue(new BigDecimal("97800.00"));
        // 设置示例持仓对应的初始浮动盈亏。
        account.setProfitLoss(new BigDecimal("2600.00"));
        // 记录默认账户的初始同步时间。
        account.setLastSyncTime(LocalDateTime.now());
        // 新建账户默认处于可用状态。
        account.setStatus("ACTIVE");
        // 插入账户；MyBatis-Plus 会回填生成的账户 ID。
        accountMapper.insert(account);

        // 示例持仓用于新用户首次进入页面时展示完整结构，不会触发任何外部交易。
        Position position = new Position();
        // 将示例持仓关联到刚插入并已获得 ID 的账户。
        position.setAccountId(account.getId());
        // 设置示例证券代码。
        position.setSecurityCode("600000.SH");
        // 设置证券展示名称。
        position.setSecurityName("浦发银行");
        // 设置总持仓数量。
        position.setQuantity(new BigDecimal("10000"));
        // 设置当前可卖数量。
        position.setAvailableQuantity(new BigDecimal("10000"));
        // 设置持仓成本价。
        position.setCostPrice(new BigDecimal("9.52"));
        // 设置最近一次同步价格。
        position.setLastPrice(new BigDecimal("9.78"));
        // 设置持仓市值。
        position.setMarketValue(new BigDecimal("97800.00"));
        // 设置持仓浮动盈亏。
        position.setProfitLoss(new BigDecimal("2600.00"));
        // 设置行业分类。
        position.setIndustry("银行");
        // 设置市场地区分类。
        position.setRegion("上海");
        // 插入示例持仓。
        positionMapper.insert(position);
    }
}
