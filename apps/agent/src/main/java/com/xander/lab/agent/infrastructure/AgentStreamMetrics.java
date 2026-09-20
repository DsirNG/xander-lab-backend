package com.xander.lab.agent.infrastructure;

import com.xander.lab.common.security.ConnectionRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Agent 长连接的逐实例指标，供 Wave 2 Drain 判据使用。 */
@Component
public class AgentStreamMetrics {

    public AgentStreamMetrics(MeterRegistry meterRegistry,
                              ConnectionRegistry connectionRegistry,
                              AgentStreamProperties properties) {
        Gauge.builder("agent_sse_active", connectionRegistry,
                        registry -> registry.activeCountByKind(ConnectionRegistry.KIND_SSE))
                .description("当前 Agent 实例活跃 SSE 连接数")
                .register(meterRegistry);
        Gauge.builder("agent_ws_active", connectionRegistry,
                        registry -> registry.activeCountByKind(ConnectionRegistry.KIND_WS))
                .description("当前 Agent 实例活跃 WebSocket 连接数")
                .register(meterRegistry);
        Gauge.builder("agent_stream_drain_enabled", properties,
                        value -> value.isDrainEnabled() ? 1 : 0)
                .description("Agent 是否拒绝新建长连接")
                .register(meterRegistry);
    }
}
