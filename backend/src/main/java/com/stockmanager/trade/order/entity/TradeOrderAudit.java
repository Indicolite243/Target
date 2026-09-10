package com.stockmanager.trade.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 高风险订单操作审计实体，记录提交、撤单、状态确认和历史隐藏等操作。
 *
 * <p>审计是追加型记录，不随订单主表软删除而删除。detailJson 必须保持脱敏，
 * 不得写入密码、Token 或完整资金账号。</p>
 */
@Data
@TableName("trade_order_audit")
public class TradeOrderAudit {
    /** 审计记录主键，由MyBatis-Plus生成。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 关联订单 ID，对应trade_order.id。 */
    private Long orderId;
    /** 发起操作的用户 ID；系统任务可按业务约定记录订单所属用户。 */
    private Long userId;
    /** 操作类型，例如SUBMIT_REQUESTED、CANCEL_REQUESTED、STATUS_CHANGED。 */
    private String action;
    /** 对应请求幂等键；不适用时为空，撤单幂等依赖该字段查询。 */
    private String idempotencyKey;
    /** 操作来源，例如API、QMT_SUBMIT、QMT_STATUS_POLL。 */
    private String source;
    /** 脱敏后的结构化审计详情JSON；只保存排查所需的最小信息。 */
    private String detailJson;
    /** 审计发生时间，用于时间线排序和责任追溯。 */
    private LocalDateTime createdAt;
}
