package com.stockmanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 证券账户管理系统 Spring Boot 启动入口。
 *
 * <p>启用调度任务以运行实时组合采集、历史归档、订单对账和运行目录清理；启用异步执行以隔离
 * 风险、归因和回测等耗时任务与 HTTP 请求线程。</p>
 */
@EnableAsync
@EnableScheduling
@SpringBootApplication
public class StockManagerApplication {
    /** 启动 Spring 容器和内嵌 Web 服务器。 */
    public static void main(String[] args) {
        SpringApplication.run(StockManagerApplication.class, args);
    }
}
