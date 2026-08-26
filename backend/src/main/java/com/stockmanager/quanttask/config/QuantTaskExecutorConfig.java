package com.stockmanager.quanttask.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/** Isolates CPU/long-I/O quantitative jobs from request and QMT collection threads. */
@Configuration
public class QuantTaskExecutorConfig {
    @Bean("quantTaskExecutor")
    public ThreadPoolTaskExecutor quantTaskExecutor(
            @Value("${app.quant-task.core-pool-size:2}") int corePoolSize,
            @Value("${app.quant-task.max-pool-size:4}") int maxPoolSize,
            @Value("${app.quant-task.queue-capacity:32}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, corePoolSize));
        executor.setMaxPoolSize(Math.max(Math.max(1, corePoolSize), maxPoolSize));
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setThreadNamePrefix("quant-task-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
