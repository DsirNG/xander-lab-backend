package com.xander.lab.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class Img2ThreeAsyncConfig {

    @Bean("img2ThreeTaskExecutor")
    public TaskExecutor img2ThreeTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("img2three-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
