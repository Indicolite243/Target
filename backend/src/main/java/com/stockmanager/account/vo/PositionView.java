package com.stockmanager.account.vo;

public record PositionView(String positionId, String symbol, String securityName, String quantity,
                           String availableQuantity, String costPrice, String lastPrice,
                           String marketValue, String profitLoss, String industry, String region) {
}
