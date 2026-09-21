package com.xander.lab.automation.host;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

/** Per-instance metrics required by the V9 content-automation drain gate. */
@Component
public class ContentAutomationStreamMetrics {

    public ContentAutomationStreamMetrics(MeterRegistry meterRegistry,
                                           ContentAutomationStreamState streamState,
                                           ContentAutomationProperties properties) {
        Tags instanceTags = Tags.of("instance", instanceLabel(properties.getStreamInstanceId()));
        Gauge.builder("content_automation_sse_active", streamState,
                        ContentAutomationStreamState::activeSse)
                .description("当前内容自动化实例活跃 SSE 连接数")
                .tags(instanceTags)
                .register(meterRegistry);
        Gauge.builder("content_automation_stream_drain_enabled", properties,
                        value -> value.isStreamDrainEnabled() ? 1 : 0)
                .description("内容自动化是否拒绝新建长连接")
                .tags(instanceTags)
                .register(meterRegistry);
    }

    private String instanceLabel(String instanceId) {
        return instanceId == null || instanceId.isBlank() ? "unknown" : instanceId;
    }
}
