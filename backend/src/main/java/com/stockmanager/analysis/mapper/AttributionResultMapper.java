package com.stockmanager.analysis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stockmanager.analysis.entity.AttributionResult;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code attribution_result}归因结果表的数据访问接口。
 * 继承BaseMapper后已具备增删改查，当前幂等查询由持久化服务使用LambdaQueryWrapper构造，
 * 因此不需要额外XML SQL。
 */
@Mapper
public interface AttributionResultMapper extends BaseMapper<AttributionResult> {
    // 后续若需要历史结果分页或按数据版本筛选，可在此增加专用查询方法。
}
