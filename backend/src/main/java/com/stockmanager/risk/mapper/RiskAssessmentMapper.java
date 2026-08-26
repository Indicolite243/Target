package com.stockmanager.risk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stockmanager.risk.entity.RiskAssessmentRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RiskAssessmentMapper extends BaseMapper<RiskAssessmentRecord> {
}
