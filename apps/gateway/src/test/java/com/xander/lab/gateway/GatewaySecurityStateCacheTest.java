package com.xander.lab.gateway;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GatewaySecurityStateCacheTest {

    private final GatewaySecurityStateCache cache = new GatewaySecurityStateCache();

    @Test
    void appliesOnlyNewerSecurityVersions() {
        cache.apply(42L, true, 2L);
        cache.apply(42L, false, 1L);

        assertThat(cache.isDisabled(42L)).isTrue();

        cache.markSourceWatermark(5L);
        assertThat(cache.reconcileLag()).isEqualTo(5L);
        cache.advanceCursor(4L);
        assertThat(cache.reconcileLag()).isEqualTo(1L);

        cache.apply(42L, false, 3L);
        assertThat(cache.isDisabled(42L)).isFalse();

        cache.apply(42L, true, 2L);
        assertThat(cache.isDisabled(42L)).isFalse();
    }

    @Test
    void recordsLatencyOnlyForAnAcceptedNewerDisabledState() {
        cache.apply(42L, true, 2L, System.currentTimeMillis() - 50L);
        long firstLatency = cache.lastPropagationLatencyMillis();

        cache.apply(42L, true, 1L, System.currentTimeMillis() - 5_000L);

        assertThat(firstLatency).isGreaterThanOrEqualTo(0L);
        assertThat(cache.lastPropagationLatencyMillis()).isEqualTo(firstLatency);
    }
}
