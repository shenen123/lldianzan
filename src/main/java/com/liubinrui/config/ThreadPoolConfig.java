package com.liubinrui.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 统一线程池配置
 */
@Configuration
public class ThreadPoolConfig {


    /**
     * Redis 操作线程池
     * 适用场景：批量 Redis 写操作，如更新活跃粉丝、记录日志等
     */
    @Bean("redisExecutor")
    public Executor redisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数：CPU核心数 × 2
        executor.setCorePoolSize(Runtime.getRuntime().availableProcessors() * 2);
        // 最大线程数：核心线程数 × 2
        executor.setMaxPoolSize(Runtime.getRuntime().availableProcessors() * 4);
        // 队列容量：避免内存溢出
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("redis-");
        executor.setThreadNamePrefix("db-");
        // 添加优雅关闭配置
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        //DiscardPolicy 会静默丢弃任务，不抛异常、不记录日志。如果Redis写入任务丢失（如活跃粉丝更新、日志记录），数据将永久丢失
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }

    /**
     * 数据库操作线程池
     * 适用场景：批量数据库写操作、数据同步
     */
    @Bean("dbExecutor")
    public Executor dbExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("db-");
        // 添加优雅关闭配置
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }


    @Bean("mqExecutor")
    public Executor mqExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("mq-");
        // 优雅关闭
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }

    // 配置专用线程池
    @Bean("esSyncExecutor")
    public ThreadPoolTaskExecutor esSyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);           // 核心线程数
        executor.setMaxPoolSize(10);           // 最大线程数
        executor.setQueueCapacity(100);        // 队列容量
        executor.setThreadNamePrefix("es-sync-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        // 最多等待 60 秒
        executor.setAwaitTerminationSeconds(120);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }

    /**
     * 批量数据库查询专用线程池（新增）
     * 适用场景：批量查询大量数据
     */
    @Bean("batchDbExecutor")
    public Executor batchDbExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int cpuCores = Runtime.getRuntime().availableProcessors();

        // I/O密集型，线程数可以设大一些
        executor.setCorePoolSize(cpuCores * 4);      // 例如 16核 → 64
        executor.setMaxPoolSize(cpuCores * 8);       // 最大 128
        executor.setQueueCapacity(200);               // 队列不宜过大
        executor.setThreadNamePrefix("batch-db-");
        // 优雅关闭
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }
}
