package com.xander.lab.gateway;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GatewaySecurityMetricsTest {

    @Test
    void publishesLatencyDistributionsAndKeepsLatestSampleGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewaySecurityStateCache cache = new GatewaySecurityStateCache();
        new GatewaySecurityMetrics(registry, cache);

        cache.apply(42L, true, 2L, System.currentTimeMillis() - 20L);

        assertThat(registry.get("admin_ban_new_request_block_latency").timer().count()).isEqualTo(1L);
        assertThat(registry.get("admin_ban_propagation_latency").timer().count()).isEqualTo(1L);
        assertThat(registry.get("admin_ban_new_request_block_latency_last").gauge().value())
                .isEqualTo(cache.lastPropagationLatencyMillis());
    }
}
