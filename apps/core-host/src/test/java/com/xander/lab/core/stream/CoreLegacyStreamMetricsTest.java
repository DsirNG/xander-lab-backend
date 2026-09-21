package com.xander.lab.core.stream;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CoreLegacyStreamMetricsTest {

    @Test
    void registersDrainMetricsWithThePerInstanceLabel() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CoreHostStreamProperties properties = new CoreHostStreamProperties();
        properties.setInstanceId("core-a");
        properties.setDrainEnabled(true);

        new CoreLegacyStreamMetrics(registry, new CoreLegacyStreamState(), properties);

        assertThat(registry.get("legacy_agent_sse_active")
                .tag("instance", "core-a").gauge().getId().getTag("instance"))
                .isEqualTo("core-a");
        assertThat(registry.get("legacy_content_sse_active")
                .tag("instance", "core-a").gauge().getId().getTag("instance"))
                .isEqualTo("core-a");
        assertThat(registry.get("core_legacy_stream_drain_enabled")
                .tag("instance", "core-a").gauge().getId().getTag("instance"))
                .isEqualTo("core-a");
        assertThat(registry.get("core_legacy_stream_drain_enabled")
                .tag("instance", "core-a").gauge().value())
                .isEqualTo(1.0d);
    }

    @Test
    void usesUnknownWhenTheInstanceIdIsMissing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CoreHostStreamProperties properties = new CoreHostStreamProperties();
        properties.setInstanceId(" ");

        new CoreLegacyStreamMetrics(registry, new CoreLegacyStreamState(), properties);

        assertThat(registry.get("legacy_agent_sse_active")
                .tag("instance", "unknown").gauge().getId().getTag("instance"))
                .isEqualTo("unknown");
    }
}
