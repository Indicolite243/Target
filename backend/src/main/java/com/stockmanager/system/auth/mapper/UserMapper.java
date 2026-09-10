// 声明用户 Mapper 所属的包。
package com.stockmanager.system.auth.mapper;

// 引入 MyBatis-Plus 通用 Mapper 基类。
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
// 引入需要操作的用户实体。
import com.stockmanager.system.auth.entity.User;
// 引入 @Mapper，使 MyBatis 注册该接口的代理实现。
import org.apache.ibatis.annotations.Mapper;

// 提供 sys_user 表的数据访问能力，基础 CRUD 由 BaseMapper 自动提供。
@Mapper
public interface UserMapper extends BaseMapper<User> {
    // 当前没有自定义 SQL，直接复用 BaseMapper 的通用方法。
}
