package com.xander.lab.agent.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Dedicated bounded workers and schedulers for agent conversation streams and leases. */
@Configuration
public class AgentAsyncConfig {

    /**
     * 执行整轮智能体的工作线程池。
     *
     * <p>一轮对话会独占一个线程直到跑完（可能包含多次模型往返 + 工具调用，长达数十秒），
     * 所以池子大小直接等于"同时能跑几轮"。早先 core=2 意味着第 3 个用户就开始排队，
     * 队列满 16 之后直接 503——并发压力会被误读成"响应慢"。
     * 这些线程绝大部分时间在等上游模型 IO（阻塞而非算力），因此可以给得比 CPU 核数大。</p>
     */
    @Bean("agentTaskExecutor")
    public TaskExecutor agentTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(16);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("agent-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    /**
     * 事件订阅（SSE / WebSocket 跟随）线程池。
     *
     * <p>每个订阅会占住一个线程直到该轮出现终态，所以它同样按"并发订阅数"而不是 CPU 来定；
     * core 太小会让订阅在池外排队，表现为"连接建立后迟迟收不到第一条事件"。</p>
     */
    @Bean("agentSseExecutor")
    public TaskExecutor agentSseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(16);
        executor.setMaxPoolSize(64);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("agent-sse-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    /**
     * 连接保活调度器。心跳是纯发送动作，但任一发送阻塞都会拖住其余会话，
     * 单线程会把这个延迟放大到所有在线连接上，故给多个线程。
     */
    @Bean("agentHeartbeatScheduler")
    public ThreadPoolTaskScheduler agentHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("agent-heartbeat-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    /** Renews durable worker leases independently of client SSE heartbeats. */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService agentLeaseRenewer() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-lease-renewer");
            thread.setDaemon(true);
            return thread;
        });
    }
}
