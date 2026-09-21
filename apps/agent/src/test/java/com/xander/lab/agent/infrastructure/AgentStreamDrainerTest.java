package com.xander.lab.agent.infrastructure;

import com.xander.lab.common.security.ConnectionRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentStreamDrainerTest {

    @Test
    void deadlineClosesSseAndWsConnectionsOnceAndRecordsInstanceMetric() {
        AgentStreamProperties properties = new AgentStreamProperties();
        properties.setInstanceId("agent-a");
        properties.setDrainEnabled(true);
        properties.setDrainDeadlineAt(System.currentTimeMillis() - 1L);
        ConnectionRegistry registry = new ConnectionRegistry();
        ConnectionRegistry.ConnectionHandle sse = mock(ConnectionRegistry.ConnectionHandle.class);
        ConnectionRegistry.ConnectionHandle ws = mock(ConnectionRegistry.ConnectionHandle.class);
        org.mockito.Mockito.when(sse.accountId()).thenReturn(1L);
        org.mockito.Mockito.when(ws.accountId()).thenReturn(2L);
        registry.register(sse);
        registry.register(ws);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AgentStreamDrainer drainer = new AgentStreamDrainer(properties, registry, meters);

        drainer.reconcileDeadline();
        drainer.reconcileDeadline();

        verify(sse).close(ConnectionRegistry.REASON_STREAM_MIGRATION);
        verify(ws).close(ConnectionRegistry.REASON_STREAM_MIGRATION);
        assertThat(meters.get("drain_connection_close_rate")
                .tag("instance", "agent-a").counter().count()).isEqualTo(2D);
    }
}
