package com.xander.lab.automation.host;

import com.xander.lab.common.security.ConnectionRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ContentAutomationStreamDrainerTest {

    @Test
    void deadlineClosesContentConnectionsOnceAndRecordsInstanceMetric() {
        ContentAutomationProperties properties = new ContentAutomationProperties();
        properties.setStreamInstanceId("content-a");
        properties.setStreamDrainEnabled(true);
        properties.setStreamDrainDeadlineAt(System.currentTimeMillis() - 1L);
        ConnectionRegistry registry = new ConnectionRegistry();
        ConnectionRegistry.ConnectionHandle handle = mock(ConnectionRegistry.ConnectionHandle.class);
        org.mockito.Mockito.when(handle.accountId()).thenReturn(7L);
        registry.register(handle);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ContentAutomationStreamDrainer drainer = new ContentAutomationStreamDrainer(
                properties, registry, meters);

        drainer.reconcileDeadline();
        drainer.reconcileDeadline();

        verify(handle).close(ConnectionRegistry.REASON_STREAM_MIGRATION);
        assertThat(meters.get("drain_connection_close_rate")
                .tag("instance", "content-a").counter().count()).isEqualTo(1D);
    }
}
