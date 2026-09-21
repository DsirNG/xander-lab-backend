package com.xander.lab.blogagent.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Bounded worker for content-owned automation plan runs. */
@Configuration
public class ContentPlanAsyncConfig {

    @Bean("contentBlogPlanRunExecutor")
    public TaskExecutor contentBlogPlanRunExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("content-blog-plan-run-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
