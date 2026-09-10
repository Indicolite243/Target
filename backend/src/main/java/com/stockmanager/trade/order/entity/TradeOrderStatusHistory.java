package com.stockmanager.trade.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单状态观测历史。
 *
 * <p>每次状态、累计成交量或平均成交价发生变化都追加新行，不覆盖旧记录，
 * 因此可以还原订单从提交到终态的完整时间线。</p>
 */
@Data
@TableName("trade_order_status_history")
public class TradeOrderStatusHistory {
    /** 历史记录主键，由MyBatis-Plus生成。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 关联订单 ID，对应trade_order.id。 */
    private Long orderId;
    /** 变化前标准状态；首次创建订单时为空。 */
    private String previousStatus;
    /** 变化后标准状态，必须是OrderStatusPolicy认可的应用状态。 */
    private String currentStatus;
    /** 本次观测到的累计成交数量，用于判断部分成交和全部成交。 */
    private BigDecimal filledQuantity;
    /** 本次观测到的平均成交价；没有成交时可以为空。 */
    private BigDecimal averageFilledPrice;
    /** 状态来源，例如WEB、QMT_SUBMIT、QMT_STATUS_POLL。 */
    private String source;
    /** 券商返回的简要状态消息；Service写入前会截断长度。 */
    private String brokerMessage;
    /** 外部状态被观测的时间，用于按时间排序还原状态变化。 */
    private LocalDateTime observedAt;
    /** 本地记录创建时间，通常与observedAt相同。 */
    private LocalDateTime createdAt;
}
