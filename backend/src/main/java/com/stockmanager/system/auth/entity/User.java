// 声明用户实体所属的包。
package com.stockmanager.system.auth.entity;

// 引入 MyBatis-Plus 主键生成策略。
import com.baomidou.mybatisplus.annotation.IdType;
// 引入主键映射注解。
import com.baomidou.mybatisplus.annotation.TableId;
// 引入实体与数据库表的映射注解。
import com.baomidou.mybatisplus.annotation.TableName;
// 引入 Lombok 的 @Data，自动生成 getter、setter 等方法。
import lombok.Data;

// 引入创建时间和更新时间使用的时间类型。
import java.time.LocalDateTime;

// 用户数据库实体；密码字段只保存不可逆的编码摘要。
@Data
// 指定该实体对应数据库中的 sys_user 表。
@TableName("sys_user")
public class User {
    // 使用 MyBatis-Plus ASSIGN_ID 策略生成用户主键。
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    // 用户唯一登录名。
    private String username;
    // 密码编码器生成的摘要，不能通过接口直接返回。
    private String passwordHash;
    // 页面显示名称。
    private String displayName;
    // 当前角色代码，例如 USER 或 ADMIN。
    private String roleCode;
    // 当前账户状态，例如 ACTIVE 或 DISABLED。
    private String status;
    // 用户记录创建时间。
    private LocalDateTime createdAt;
    // 用户记录最后更新时间。
    private LocalDateTime updatedAt;
}
