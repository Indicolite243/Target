package com.stockmanager.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stockmanager.account.entity.AccountHistorySnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * {@code account_snapshot} 账户资产历史表的数据访问接口。
 *
 * <p>普通 CRUD 继承 BaseMapper；下面两条“最近完整持仓快照”查询包含排序和 LIMIT，
 * 属于稳定且对性能敏感的访问模式，因此显式写成 SQL，便于审查索引命中情况。</p>
 */
@Mapper
public interface AccountHistorySnapshotMapper extends BaseMapper<AccountHistorySnapshot> {
    /**
     * 查询指定时刻之前最近一条包含完整持仓的账户快照。
     * {@code snapshot_time DESC, id DESC} 在同毫秒多行时仍能确定唯一最新记录。
     */
    @Select("""
            SELECT * FROM account_snapshot
            WHERE account_id = #{accountId} AND positions_captured = 1 AND snapshot_time <= #{at}
            ORDER BY snapshot_time DESC, id DESC LIMIT 1
            """)
    AccountHistorySnapshot findLatestWithPositionsAtOrBefore(@Param("accountId") Long accountId,
                                                              @Param("at") LocalDateTime at);

    /**
     * 查询指定时刻之后第一条包含完整持仓的账户快照。
     * 当区间起点之前没有可用快照时，分析服务用它取得最接近的后续事实。
     */
    @Select("""
            SELECT * FROM account_snapshot
            WHERE account_id = #{accountId} AND positions_captured = 1 AND snapshot_time >= #{at}
            ORDER BY snapshot_time ASC, id ASC LIMIT 1
            """)
    AccountHistorySnapshot findFirstWithPositionsAtOrAfter(@Param("accountId") Long accountId,
                                                            @Param("at") LocalDateTime at);
}
