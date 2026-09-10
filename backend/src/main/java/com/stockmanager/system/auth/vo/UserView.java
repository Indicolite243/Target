// 声明用户响应 VO 所属的包。
package com.stockmanager.system.auth.vo;

// 引入 List，用于表示角色集合和权限集合。
import java.util.List;

// 用户安全视图；刻意不暴露数据库实体中的 passwordHash。
public record UserView(
        // 用户主键以字符串返回，避免前端处理长整型时发生精度问题。
        String userId,
        // 用户唯一登录名。
        String username,
        // 页面展示名称。
        String displayName,
        // 用户角色代码列表，例如 USER 或 ADMIN。
        List<String> roles,
        // 根据角色计算出的权限代码列表。
        List<String> permissions,
        // 用户当前状态，例如 ACTIVE 或 DISABLED。
        String status
) {
    // record 自动生成构造方法、访问器和常用值对象方法。
}
