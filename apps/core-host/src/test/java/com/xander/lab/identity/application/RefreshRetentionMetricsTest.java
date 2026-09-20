package com.xander.lab.identity.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshRetentionMetricsTest {

    @Test
    void recordsCleanupRowsDurationAndLatestRate() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RefreshRetentionMetrics metrics = new RefreshRetentionMetrics(registry);

        metrics.recordHistoryCleanup(100, 2_000_000_000L);

        assertThat(registry.get("history_cleanup_rate").counter().count()).isEqualTo(100D);
        assertThat(registry.get("history_cleanup_duration").timer().count()).isEqualTo(1L);
        assertThat(registry.get("history_cleanup_latest_rate").gauge().value()).isEqualTo(50D);
    }

    @Test
    void zeroRowCleanupStillRecordsDurationAndReportsZeroRate() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RefreshRetentionMetrics metrics = new RefreshRetentionMetrics(registry);

        metrics.recordHistoryCleanup(0, 100_000_000L);

        assertThat(registry.get("history_cleanup_rate").counter().count()).isZero();
        assertThat(registry.get("history_cleanup_duration").timer().count()).isEqualTo(1L);
        assertThat(registry.get("history_cleanup_latest_rate").gauge().value()).isZero();
    }
}
