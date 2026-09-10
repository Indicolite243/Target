package com.stockmanager.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** MySQL 当前账户资产实体；保存最近一次已确认快照，不承担历史序列职责。 */
@Data
@TableName("account")
public class Account {
    /** 应用生成的 Snowflake 主键。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 账户所属系统用户。 */
    private Long userId;
    /** 券商资金账号或系统模拟账号，接口输出时应脱敏。 */
    private String accountNo;
    /** 页面展示名称。 */
    private String accountName;
    /** 券商/适配器类型，例如 GUOJIN_QMT、SIMULATOR。 */
    private String broker;
    /** 账户环境，例如 SIMULATION。 */
    private String environment;
    /** 资金币种，当前通常为 CNY。 */
    private String currency;
    /** 总资产。 */
    private BigDecimal totalAsset;
    /** 可用资金。 */
    private BigDecimal cash;
    /** 当前持仓总市值。 */
    private BigDecimal marketValue;
    /** 当前累计浮动盈亏。 */
    private BigDecimal profitLoss;
    /** 最近一次成功同步时间。 */
    private LocalDateTime lastSyncTime;
    /** 当前组合单调递增版本，用于缓存一致性和前端变更判断。 */
    private Long dataVersion;
    /** 账户业务状态，例如 ACTIVE、DISABLED。 */
    private String status;
}
