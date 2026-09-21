package com.xander.lab.agent.infrastructure;

import com.xander.lab.common.security.ConnectionRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentStreamMetricsTest {

    @Test
    void registersActiveStreamMetricsWithInstanceLabel() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AgentStreamProperties properties = new AgentStreamProperties();
        properties.setInstanceId("agent-a");

        new AgentStreamMetrics(meters, new ConnectionRegistry(), properties);

        assertThat(meters.get("agent_sse_active").tag("instance", "agent-a").gauge()
                .getId().getTag("instance")).isEqualTo("agent-a");
        assertThat(meters.get("agent_ws_active").tag("instance", "agent-a").gauge()
                .getId().getTag("instance")).isEqualTo("agent-a");
    }
}
