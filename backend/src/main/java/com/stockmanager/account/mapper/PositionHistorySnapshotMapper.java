package com.stockmanager.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stockmanager.account.entity.PositionHistorySnapshot;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code position_snapshot} 历史持仓明细表的数据访问接口。
 *
 * <p>写入端按账户快照 ID 追加不可变持仓行，读取端通过 LambdaQueryWrapper 按 snapshot_id
 * 获取整组明细。现有场景都是单表条件查询，BaseMapper 足够；若以后增加跨快照收益统计，
 * 可在这里添加显式聚合 SQL，而不是把 SQL 混入 Service。</p>
 */
@Mapper
public interface PositionHistorySnapshotMapper extends BaseMapper<PositionHistorySnapshot> {
    // 当前不声明自定义方法；BaseMapper 的 insert/selectList 覆盖已有历史持仓访问场景。
}
