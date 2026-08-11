package com.stockmanager.account.service.impl;

import com.stockmanager.account.entity.Account;
import com.stockmanager.account.entity.Position;
import com.stockmanager.account.mapper.AccountMapper;
import com.stockmanager.account.mapper.PositionMapper;
import com.stockmanager.account.service.AccountProvisioningService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
public class AccountProvisioningServiceImpl implements AccountProvisioningService {
    private final AccountMapper accountMapper;
    private final PositionMapper positionMapper;

    public AccountProvisioningServiceImpl(AccountMapper accountMapper, PositionMapper positionMapper) {
        this.accountMapper = accountMapper;
        this.positionMapper = positionMapper;
    }

    @Override
    public void createSimulationAccount(Long userId, String username) {
        Account account = new Account();
        account.setUserId(userId);
        account.setAccountNo("SIM-" + userId);
        account.setAccountName(username + "的模拟账户");
        account.setBroker("SIMULATOR");
        account.setEnvironment("SIMULATION");
        account.setCurrency("CNY");
        account.setTotalAsset(new BigDecimal("1000000.00"));
        account.setCash(new BigDecimal("902200.00"));
        account.setMarketValue(new BigDecimal("97800.00"));
        account.setProfitLoss(new BigDecimal("2600.00"));
        account.setLastSyncTime(LocalDateTime.now());
        account.setStatus("ACTIVE");
        accountMapper.insert(account);

        Position position = new Position();
        position.setAccountId(account.getId());
        position.setSecurityCode("600000.SH");
        position.setSecurityName("浦发银行");
        position.setQuantity(new BigDecimal("10000"));
        position.setAvailableQuantity(new BigDecimal("10000"));
        position.setCostPrice(new BigDecimal("9.52"));
        position.setLastPrice(new BigDecimal("9.78"));
        position.setMarketValue(new BigDecimal("97800.00"));
        position.setProfitLoss(new BigDecimal("2600.00"));
        position.setIndustry("银行");
        position.setRegion("上海");
        positionMapper.insert(position);
    }
}
