package com.stockmanager.quanttask.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 量化任务专用线程池配置。
 *
 * <p>风险、归因和回测通常包含长时间计算或外部 HTTP I/O，不能占用 Tomcat 请求线程，
 * 也不能与两秒一次的 QMT 账户采集线程混用，因此单独建立一个有界线程池。</p>
 */
@Configuration
public class QuantTaskExecutorConfig {
    /**
     * 创建风险、归因和回测共用的有界线程池，避免耗时任务挤占 HTTP 与实时采集线程。
     *
     * @param corePoolSize 常驻核心线程数，默认同时执行两个任务
     * @param maxPoolSize 队列满后允许扩展到的最大线程数
     * @param queueCapacity 等待执行的最大任务数量
     * @return 已完成初始化、可通过名称注入的 Spring 任务执行器
     */
    @Bean("quantTaskExecutor")
    public ThreadPoolTaskExecutor quantTaskExecutor(
            // 配置支持环境变量覆盖；Math.max 会在下方把错误的零或负值收敛到安全下限。
            @Value("${app.quant-task.core-pool-size:2}") int corePoolSize,
            @Value("${app.quant-task.max-pool-size:4}") int maxPoolSize,
            @Value("${app.quant-task.queue-capacity:32}") int queueCapacity) {
        // ThreadPoolTaskExecutor 是 Spring 对 JDK ThreadPoolExecutor 的生命周期封装。
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 至少保留一个核心线程，防止错误配置导致任务永远没有消费者。
        executor.setCorePoolSize(Math.max(1, corePoolSize));
        // 最大线程数不能小于核心线程数，否则线程池初始化会失败。
        executor.setMaxPoolSize(Math.max(Math.max(1, corePoolSize), maxPoolSize));
        // 使用有界队列限制内存占用；队列容量至少为一。
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        // 线程名前缀便于在日志、线程转储和性能分析工具中识别量化任务。
        executor.setThreadNamePrefix("quant-task-");
        // 关闭应用时不无限等待长任务；重启恢复器会把遗留任务标记为 FAILED。
        executor.setWaitForTasksToCompleteOnShutdown(false);
        // 达到“最大线程 + 队列容量”后直接抛 RejectedExecutionException，由提交服务记录 TASK_QUEUE_FULL。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 显式初始化底层线程池，确保 Bean 返回后可以立即接收任务。
        executor.initialize();
        return executor;
    }
}
