package com.stockmanager.trade.order.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SubmitOrderRequest(
        @NotBlank String accountId,
        @NotBlank String symbol,
        String securityName,
        @NotBlank @Pattern(regexp = "BUY|SELL") String side,
        @NotBlank @Pattern(regexp = "LIMIT|MARKET") String orderType,
        @NotBlank @DecimalMin(value = "0", inclusive = false) String quantity,
        String price,
        @NotBlank @Pattern(regexp = "SIMULATION|REAL") String environment,
        String remark
) {
}
