package com.stockmanager.quanttask.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stockmanager.quanttask.entity.QuantTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code quant_task} 异步任务状态表的数据访问接口。
 *
 * <p>继承 MyBatis-Plus {@link BaseMapper} 后已经拥有 insert、selectById、update、
 * 条件查询和条件更新能力，因此这里不需要手写 XML SQL。任务状态机所需的 CAS 更新条件
 * 由 {@code QuantTaskService} 使用 LambdaUpdateWrapper 动态构造。</p>
 */
@Mapper
public interface QuantTaskMapper extends BaseMapper<QuantTask> {
    // 当前没有模块专用SQL；保留独立Mapper是为了隔离数据访问边界并便于以后增加锁任务、分页查询等语句。
}
