package com.xander.lab.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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
}
