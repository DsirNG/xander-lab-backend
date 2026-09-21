package com.xander.lab.blogagent.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Bounded worker for content-owned BlogAgent SSE streams. */
@Configuration
public class BlogAgentStreamConfig {

    @Bean("contentBlogAgentStreamExecutor")
    public TaskExecutor contentBlogAgentStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("content-blog-agent-sse-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
