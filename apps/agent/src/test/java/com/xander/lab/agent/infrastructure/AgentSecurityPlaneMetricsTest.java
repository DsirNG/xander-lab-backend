package com.xander.lab.agent.infrastructure;

import com.xander.lab.common.security.ConnectionRegistry;
import com.xander.lab.common.security.SecurityStateCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentSecurityPlaneMetricsTest {

    @Test
    void registersSecurityGaugesAndRecordsBanLatency() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SecurityStateCache cache = new SecurityStateCache();
        new AgentSecurityPlaneMetrics(registry, cache, new ConnectionRegistry());

        cache.markDisabled(42L, 7L);
        cache.recordPropagationLatency(System.currentTimeMillis() - 10L);
        cache.recordStreamDisconnectLatency(System.currentTimeMillis() - 10L);

        assertThat(registry.get("service_security_cache_size").gauge().value()).isEqualTo(1D);
        assertThat(registry.get("security_reconcile_cursor").gauge().value()).isZero();
        assertThat(registry.get("admin_ban_new_request_block_latency").timer().count()).isEqualTo(1L);
        assertThat(registry.get("admin_ban_propagation_latency").timer().count()).isEqualTo(1L);
        assertThat(registry.get("admin_ban_stream_disconnect_latency").timer().count()).isEqualTo(1L);
    }
}
