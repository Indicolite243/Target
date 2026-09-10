package com.stockmanager.risk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stockmanager.risk.entity.RiskAssessmentRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code risk_assessment}风险结果表的数据访问接口。
 * 继承BaseMapper即可完成主键查询、插入和任务ID补绑，当前没有必须手写的复杂SQL。
 */
@Mapper
public interface RiskAssessmentMapper extends BaseMapper<RiskAssessmentRecord> {
    // 专用历史分页或按版本查询可在需要时添加，避免提前维护无调用方SQL。
}
