package com.xander.lab.blogagent.infrastructure;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Owns the independent scheduler used to renew durable blog-agent task leases. */
@Component
public class BlogAgentLeaseScheduler {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "blog-agent-lease-renewer");
        thread.setDaemon(true);
        return thread;
    });

    /** Schedules a fixed-rate renewal after one full interval. */
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable renewal, long intervalSeconds) {
        return executor.scheduleAtFixedRate(
                renewal, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
