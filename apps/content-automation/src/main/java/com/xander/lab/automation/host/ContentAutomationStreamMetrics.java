package com.xander.lab.automation.host;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Per-instance metrics required by the V9 content-automation drain gate. */
@Component
public class ContentAutomationStreamMetrics {

    public ContentAutomationStreamMetrics(MeterRegistry meterRegistry,
                                           ContentAutomationStreamState streamState,
                                           ContentAutomationProperties properties) {
        Gauge.builder("content_automation_sse_active", streamState,
                        ContentAutomationStreamState::activeSse)
                .description("当前内容自动化实例活跃 SSE 连接数")
                .register(meterRegistry);
        Gauge.builder("content_automation_stream_drain_enabled", properties,
                        value -> value.isStreamDrainEnabled() ? 1 : 0)
                .description("内容自动化是否拒绝新建长连接")
                .register(meterRegistry);
    }
}
