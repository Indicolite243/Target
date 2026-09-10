package com.stockmanager.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** MySQL 当前持仓实体；每次全量同步时按账户整体替换。 */
@Data
@TableName("position")
public class Position {
    /** 应用生成的 Snowflake 主键。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 所属账户 ID。 */
    private Long accountId;
    /** 标准证券代码，包含市场后缀。 */
    private String securityCode;
    /** 证券展示名称。 */
    private String securityName;
    /** 总持仓数量。 */
    private BigDecimal quantity;
    /** 当前可卖数量。 */
    private BigDecimal availableQuantity;
    /** 持仓成本价。 */
    private BigDecimal costPrice;
    /** 最近一次同步价格。 */
    private BigDecimal lastPrice;
    /** 持仓市值。 */
    private BigDecimal marketValue;
    /** 当前持仓盈亏。 */
    private BigDecimal profitLoss;
    /** 行业分类；缺失时展示层可做兼容推断。 */
    private String industry;
    /** 交易市场或地区分类。 */
    private String region;
}
