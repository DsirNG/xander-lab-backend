package com.xander.lab.core.stream;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** V9 §54/§55 per-instance metrics for the Core legacy-stream drain. */
@Component
public class CoreLegacyStreamMetrics {

    public CoreLegacyStreamMetrics(MeterRegistry meterRegistry,
                                   CoreLegacyStreamState streamState,
                                   CoreHostStreamProperties properties) {
        Gauge.builder("legacy_agent_sse_active", streamState,
                        CoreLegacyStreamState::legacyAgentSseActive)
                .description("当前 Core 实例仍存量的旧 BlogAgent SSE 连接数")
                .register(meterRegistry);
        Gauge.builder("legacy_content_sse_active", streamState,
                        CoreLegacyStreamState::legacyContentSseActive)
                .description("当前 Core 实例仍存量的旧内容通知 SSE 连接数")
                .register(meterRegistry);
        Gauge.builder("core_legacy_stream_drain_enabled", properties,
                        value -> value.isDrainEnabled() ? 1 : 0)
                .description("Core 是否拒绝新建旧内容长连接")
                .register(meterRegistry);
    }
}
