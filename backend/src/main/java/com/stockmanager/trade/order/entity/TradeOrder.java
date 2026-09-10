package com.stockmanager.trade.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 本地订单主实体，保存用户交易意图、券商标识、成交进度和当前标准状态。
 * 状态变化明细与操作审计分别保存在独立历史表中。
 */
@Data
@TableName("trade_order")
public class TradeOrder {
    /**
     * 应用订单主键。
     *
     * <p>ASSIGN_ID由MyBatis-Plus生成Snowflake ID；对外输出时必须转成字符串，
     * 否则JavaScript Number可能无法精确表示较大的Long。</p>
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 交易账户 ID；与account表关联，Service查询时还会校验user_id归属。 */
    private Long accountId;
    /** 下单用户 ID，用于资源归属和幂等隔离，禁止通过订单ID跨用户访问。 */
    private Long userId;
    /** 用户级请求幂等键；数据库对(user_id,idempotency_key)建立唯一约束。 */
    private String idempotencyKey;
    /** 应用生成的客户端订单号；供前端展示、QMT备注和跨服务日志关联。 */
    private String clientOrderNo;
    /** QMT/券商返回的外部订单号；只有外部调用成功后才会填充。 */
    private String externalOrderNo;
    /** 标准证券代码，例如600000.SH或510300.SH。 */
    private String symbol;
    /** 证券展示名称；行情或QMT状态回报可能补充/修正该字段。 */
    private String securityName;
    /** 买卖方向，例如 BUY、SELL；数据库保存应用层可读值。 */
    private String side;
    /** 委托类型，例如 LIMIT、MARKET；由QMT适配器映射为数字枚举。 */
    private String orderType;
    /** 委托数量；使用BigDecimal保留协议精度，适配器调用QMT时转换为整数。 */
    private BigDecimal quantity;
    /** 限价；市价单可为空，传给QMT时以0表示市价。 */
    private BigDecimal price;
    /** 累计成交数量；由QMT状态轮询逐步更新，不代表单次成交数量。 */
    private BigDecimal filledQuantity;
    /** 平均成交价格；部分成交时可能有值，未成交时为空。 */
    private BigDecimal averageFilledPrice;
    /** 应用标准订单状态；状态规则集中在OrderStatusPolicy中。 */
    private String status;
    /** 交易环境，必须与账户环境一致，例如SIMULATION或REAL。 */
    private String environment;
    /** 用户备注；不参与幂等、撤单定位或状态判断。 */
    private String remark;
    /** 本地意图创建时间；在首次创建PENDING_SUBMIT时写入。 */
    private LocalDateTime createdAt;
    /** 最近状态更新时间；主表任何状态或成交字段变化都会更新。 */
    private LocalDateTime updatedAt;
    /** 用户清理历史时使用的软删除时间；设置该字段绝不会撤销券商订单。 */
    private LocalDateTime deletedAt;
}
