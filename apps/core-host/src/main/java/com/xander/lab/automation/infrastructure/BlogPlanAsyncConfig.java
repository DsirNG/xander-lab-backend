package com.xander.lab.automation.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Bounded workers for long-running content-plan runs (generation + publish). */
@Configuration
public class BlogPlanAsyncConfig {

    @Bean("blogPlanRunExecutor")
    public TaskExecutor blogPlanRunExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("blog-plan-run-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}