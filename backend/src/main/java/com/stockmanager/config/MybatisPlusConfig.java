package com.stockmanager.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 基础配置，当前主要注册 MySQL 物理分页拦截器。
 *
 * <p>需要特别区分三个概念：</p>
 * <ul>
 *     <li>MyBatis 是 SQL 映射框架，Mapper 接口仍然是 Java 访问数据库的边界；</li>
 *     <li>MyBatis-Plus 在 Mapper 上补充通用 CRUD、条件构造器和分页能力，不会取消 Mapper 层；</li>
 *     <li>复杂联表、批量写入、状态条件更新等业务 SQL 仍应在 Mapper/XML/注解 SQL 中明确表达。</li>
 * </ul>
 *
 * <p>因此项目中“BaseMapper 通用方法 + 少量自定义 SQL”是正常组合：简单查询减少模板代码，
 * 对性能和原子性有要求的 SQL 则保持显式、可审计。</p>
 */
@Configuration
public class MybatisPlusConfig {
    /**
     * 创建包含 MySQL 物理分页能力的拦截器链。
     * 调用 Mapper 的分页查询时，该插件会根据 MySQL 方言生成 LIMIT/OFFSET，而不是把全表数据
     * 拉到 JVM 后再截取，从而降低网络传输和堆内存占用。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        // MybatisPlusInterceptor 是外层插件容器，后续可继续追加乐观锁等 InnerInterceptor。
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 明确 DbType.MYSQL，避免运行时猜测数据库方言并确保分页 SQL 语法正确。
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
