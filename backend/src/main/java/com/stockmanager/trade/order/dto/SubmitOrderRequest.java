package com.stockmanager.trade.order.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 新委托请求。
 *
 * @param accountId 交易账户 ID，使用字符串避免前端 Snowflake 精度丢失
 * @param symbol 标准证券代码
 * @param securityName 可选证券名称
 * @param side BUY 或 SELL
 * @param orderType LIMIT 或 MARKET
 * @param quantity 大于零的十进制数量字符串
 * @param price 限价单价格；市价单可为空
 * @param environment SIMULATION 或 REAL，必须与账户一致
 * @param remark 可选用户备注
 */
public record SubmitOrderRequest(
        // 账户主键使用字符串接收，避免浏览器处理Snowflake Long时发生精度丢失。
        @NotBlank String accountId,
        // 证券代码，例如600000.SH；Service会再次去空格并交给QMT适配器标准化。
        @NotBlank String symbol,
        // 展示名称可选；为空时Service回退使用证券代码。
        String securityName,
        // 买卖方向只允许BUY/SELL，正则在Controller进入Service前完成第一层校验。
        @NotBlank @Pattern(regexp = "BUY|SELL") String side,
        // 委托价格类型只允许LIMIT/MARKET；限价单必须另外提供正价格。
        @NotBlank @Pattern(regexp = "LIMIT|MARKET") String orderType,
        // 数量以字符串传输，避免JSON浮点误差；必须大于0，Service再转BigDecimal。
        @NotBlank @DecimalMin(value = "0", inclusive = false) String quantity,
        // 限价单价格；市价单可以为空，空值不会被强行转换成0写入主表。
        String price,
        // 交易环境必须与账户环境一致，防止把模拟账户请求误发到真实通道。
        @NotBlank @Pattern(regexp = "SIMULATION|REAL") String environment,
        // 用户备注只用于本地展示和审计上下文，不参与订单定位。
        String remark
) {
}
