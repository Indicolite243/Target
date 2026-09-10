package com.stockmanager.account.service;

/** 新用户账户初始化服务契约。 */
public interface AccountProvisioningService {
    /** 创建项目内置模拟账户和初始示例持仓。 */
    void createSimulationAccount(Long userId, String username);
}
