package com.stockmanager.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stockmanager.account.entity.Account;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code account} 当前账户表的 MyBatis-Plus 数据访问接口。
 *
 * <p>{@link BaseMapper} 已提供按主键查询、插入、更新、删除和 Wrapper 条件查询，
 * 账户业务暂时没有需要固化为专用方法的复杂 SQL。保留独立 Mapper 层是为了让 Service
 * 不直接依赖数据库实现，并便于后续添加行锁、账户分页等查询。</p>
 */
@Mapper
public interface AccountMapper extends BaseMapper<Account> {
    // 当前账户的普通查询均由 LambdaQueryWrapper 生成，无需重复手写 XML。
}
