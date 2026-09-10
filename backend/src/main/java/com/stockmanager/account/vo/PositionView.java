package com.stockmanager.account.vo;

/** 当前持仓展示模型，ID、数量、价格和金额均使用精确十进制字符串。 */
public record PositionView(
        // 持仓记录主键。
        String positionId,
        // 标准证券代码。
        String symbol,
        // 证券展示名称。
        String securityName,
        // 总持仓数量。
        String quantity,
        // 可卖数量。
        String availableQuantity,
        // 持仓成本价。
        String costPrice,
        // 最新同步价格。
        String lastPrice,
        // 当前持仓市值。
        String marketValue,
        // 当前持仓盈亏。
        String profitLoss,
        // 行业分类。
        String industry,
        // 市场或地区分类。
        String region) {
    // record 自动生成构造方法和字段访问器。
}
