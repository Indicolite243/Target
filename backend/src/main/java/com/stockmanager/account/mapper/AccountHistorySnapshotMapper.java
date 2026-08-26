package com.stockmanager.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stockmanager.account.entity.AccountHistorySnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface AccountHistorySnapshotMapper extends BaseMapper<AccountHistorySnapshot> {
    @Select("""
            SELECT * FROM account_snapshot
            WHERE account_id = #{accountId} AND positions_captured = 1 AND snapshot_time <= #{at}
            ORDER BY snapshot_time DESC, id DESC LIMIT 1
            """)
    AccountHistorySnapshot findLatestWithPositionsAtOrBefore(@Param("accountId") Long accountId,
                                                              @Param("at") LocalDateTime at);

    @Select("""
            SELECT * FROM account_snapshot
            WHERE account_id = #{accountId} AND positions_captured = 1 AND snapshot_time >= #{at}
            ORDER BY snapshot_time ASC, id ASC LIMIT 1
            """)
    AccountHistorySnapshot findFirstWithPositionsAtOrAfter(@Param("accountId") Long accountId,
                                                            @Param("at") LocalDateTime at);
}
