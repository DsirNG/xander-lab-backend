package com.xander.lab.blogagent.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Dedicated bounded workers for long-running blog-agent streams. */
@Configuration
public class BlogAgentAsyncConfig {

    @Bean("blogAgentTaskExecutor")
    public TaskExecutor blogAgentTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("blog-agent-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    @Bean("blogAgentSseExecutor")
    public TaskExecutor blogAgentSseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("blog-agent-sse-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    @Bean("blogAgentHeartbeatScheduler")
    public ThreadPoolTaskScheduler blogAgentHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("blog-agent-heartbeat-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
