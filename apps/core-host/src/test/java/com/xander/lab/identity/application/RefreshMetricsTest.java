package com.xander.lab.identity.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshMetricsTest {

    @Test
    void recordsRotationOutcomesLatencyAndLogout() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RefreshMetrics metrics = new RefreshMetrics(registry);

        metrics.recordRotation(RefreshTokenService.Outcome.ROTATED, 1_000_000L);
        metrics.recordRotation(RefreshTokenService.Outcome.REPLAYED, 2_000_000L);
        metrics.recordRotation(RefreshTokenService.Outcome.REUSE_DETECTED, 3_000_000L);
        metrics.recordRotation(RefreshTokenService.Outcome.INVALID, 4_000_000L);
        metrics.recordRotation(RefreshTokenService.Outcome.SESSION_REVOKED, 5_000_000L);
        metrics.recordRotation(RefreshTokenService.Outcome.ACCOUNT_DISABLED, 6_000_000L);
        metrics.recordDatabaseError(7_000_000L);
        metrics.recordLogout(true);
        metrics.recordLogout(false);

        assertThat(registry.get("refresh_requests_total").counter().count()).isEqualTo(7D);
        assertThat(registry.get("refresh_success_total").counter().count()).isEqualTo(2D);
        assertThat(registry.get("refresh_success_rate").gauge().value()).isEqualTo(2D / 7D);
        assertThat(registry.get("refresh_grace_replay_total").counter().count()).isEqualTo(1D);
        assertThat(registry.get("refresh_reuse_detected_total").counter().count()).isEqualTo(1D);
        assertThat(registry.get("refresh_family_revoked_total").counter().count()).isEqualTo(1D);
        assertThat(registry.get("refresh_invalid_total").counter().count()).isEqualTo(1D);
        assertThat(registry.get("refresh_db_error_total").counter().count()).isEqualTo(1D);
        assertThat(registry.get("logout_success_total").counter().count()).isEqualTo(1D);
        assertThat(registry.get("refresh_db_latency").timer().count()).isEqualTo(7L);
    }
}
