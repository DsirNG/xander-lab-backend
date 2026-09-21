package com.xander.lab.identity.application;

import com.xander.lab.common.security.ConnectionRegistry;
import com.xander.lab.common.security.SecurityStateCache;
import com.xander.lab.core.stream.CoreHostStreamProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityPlaneMetricsTest {

    @Test
    void publishesLatencyDistributionsAndKeepsLatestSampleGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SecurityStateCache cache = new SecurityStateCache();
        ConnectionRegistry connections = new ConnectionRegistry();
        CoreHostStreamProperties properties = new CoreHostStreamProperties();
        properties.setInstanceId("core-a");
        new SecurityPlaneMetrics(registry, cache, connections, properties);

        cache.recordPropagationLatency(System.currentTimeMillis() - 20L);
        cache.recordStreamDisconnectLatency(System.currentTimeMillis() - 30L);

        assertThat(registry.get("admin_ban_new_request_block_latency").timer().count()).isEqualTo(1L);
        assertThat(registry.get("admin_ban_propagation_latency").timer().count()).isEqualTo(1L);
        assertThat(registry.get("admin_ban_stream_disconnect_latency").timer().count()).isEqualTo(1L);
        assertThat(registry.get("active_sse").tag("instance", "core-a").gauge().value()).isZero();
        assertThat(registry.get("active_ws").tag("instance", "core-a").gauge().value()).isZero();
        assertThat(registry.get("admin_ban_new_request_block_latency_last").gauge().value())
                .isEqualTo(cache.lastPropagationLatencyMillis());
        assertThat(registry.get("admin_ban_stream_disconnect_latency_last").gauge().value())
                .isEqualTo(cache.lastStreamDisconnectLatencyMillis());
    }
}
