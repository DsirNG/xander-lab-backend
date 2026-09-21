package com.xander.lab.identity.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LegacyRedisMetricsTest {

    @Test
    void recordsLegacyIssuanceRevocationAndRevokeLatency() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LegacyRedisMetrics metrics = new LegacyRedisMetrics(registry, mock(StringRedisTemplate.class));

        metrics.recordAccessIssued();
        metrics.recordAccessIssued();
        metrics.recordAccessRevoked(1L);
        metrics.recordLegacyRefreshRequest();
        metrics.recordRevokeDuration(1_000_000L);

        assertThat(registry.get("legacy_access_issued_total").counter().count()).isEqualTo(2D);
        assertThat(registry.get("legacy_access_revoked_total").counter().count()).isEqualTo(1D);
        assertThat(registry.get("legacy_refresh_requests_total").counter().count()).isEqualTo(1D);
        assertThat(registry.get("legacy_revoke_duration").timer().count()).isEqualTo(1L);
    }
}
