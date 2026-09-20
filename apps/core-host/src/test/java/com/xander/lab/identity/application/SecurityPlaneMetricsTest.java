package com.xander.lab.identity.application;

import com.xander.lab.common.security.ConnectionRegistry;
import com.xander.lab.common.security.SecurityStateCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityPlaneMetricsTest {

    @Test
    void publishesLatencyDistributionsAndKeepsLatestSampleGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SecurityStateCache cache = new SecurityStateCache();
        new SecurityPlaneMetrics(registry, cache, new ConnectionRegistry());

        cache.recordPropagationLatency(System.currentTimeMillis() - 20L);
        cache.recordStreamDisconnectLatency(System.currentTimeMillis() - 30L);

        assertThat(registry.get("admin_ban_new_request_block_latency").timer().count()).isEqualTo(1L);
        assertThat(registry.get("admin_ban_propagation_latency").timer().count()).isEqualTo(1L);
        assertThat(registry.get("admin_ban_stream_disconnect_latency").timer().count()).isEqualTo(1L);
        assertThat(registry.get("admin_ban_new_request_block_latency_last").gauge().value())
                .isEqualTo(cache.lastPropagationLatencyMillis());
        assertThat(registry.get("admin_ban_stream_disconnect_latency_last").gauge().value())
                .isEqualTo(cache.lastStreamDisconnectLatencyMillis());
    }
}
