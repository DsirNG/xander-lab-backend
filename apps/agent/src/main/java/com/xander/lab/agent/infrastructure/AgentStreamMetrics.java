package com.xander.lab.agent.infrastructure;

import com.xander.lab.common.security.ConnectionRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

/** Agent 长连接的逐实例指标，供 Wave 2 Drain 判据使用。 */
@Component
public class AgentStreamMetrics {

    public AgentStreamMetrics(MeterRegistry meterRegistry,
                              ConnectionRegistry connectionRegistry,
                              AgentStreamProperties properties) {
        Tags instanceTags = Tags.of("instance", instanceLabel(properties.getInstanceId()));
        Gauge.builder("agent_sse_active", connectionRegistry,
                        registry -> registry.activeCountByKind(ConnectionRegistry.KIND_SSE))
                .description("当前 Agent 实例活跃 SSE 连接数")
                .tags(instanceTags)
                .register(meterRegistry);
        Gauge.builder("agent_ws_active", connectionRegistry,
                        registry -> registry.activeCountByKind(ConnectionRegistry.KIND_WS))
                .description("当前 Agent 实例活跃 WebSocket 连接数")
                .tags(instanceTags)
                .register(meterRegistry);
        Gauge.builder("agent_stream_drain_enabled", properties,
                        value -> value.isDrainEnabled() ? 1 : 0)
                .description("Agent 是否拒绝新建长连接")
                .tags(instanceTags)
                .register(meterRegistry);
    }

    private String instanceLabel(String instanceId) {
        return instanceId == null || instanceId.isBlank() ? "unknown" : instanceId;
    }
}
