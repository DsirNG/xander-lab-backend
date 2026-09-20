package com.xander.lab.identity.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** V9 §87 Refresh 历史清理吞吐与耗时指标。 */
@Component
public class RefreshRetentionMetrics {

    private final Counter cleanupRows;
    private final Timer cleanupDuration;
    private final AtomicLong latestCleanupRate = new AtomicLong();

    public RefreshRetentionMetrics(MeterRegistry meterRegistry) {
        cleanupRows = Counter.builder("history_cleanup_rate")
                .description("历史清理累计删除行数；实际速率由该值的 rate() 推导")
                .register(meterRegistry);
        cleanupDuration = Timer.builder("history_cleanup_duration")
                .description("历史清理单轮耗时")
                .register(meterRegistry);
        Gauge.builder("history_cleanup_latest_rate", latestCleanupRate, AtomicLong::doubleValue)
                .description("最近一轮历史清理吞吐，单位行/秒")
                .register(meterRegistry);
    }

    public void recordHistoryCleanup(int deletedRows, long elapsedNanos) {
        if (deletedRows > 0) {
            cleanupRows.increment(deletedRows);
        }
        cleanupDuration.record(Math.max(0L, elapsedNanos), TimeUnit.NANOSECONDS);
        long elapsedMillis = Math.max(1L, elapsedNanos / 1_000_000L);
        latestCleanupRate.set(Math.max(0L,
                Math.round(deletedRows * 1000D / elapsedMillis)));
    }
}
