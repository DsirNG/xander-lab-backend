package com.xander.lab.core.stream;

import com.xander.lab.common.security.ConnectionRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class CoreLegacyStreamDrainerTest {

    @Test
    void deadlineForceClosesOnlyRegisteredLegacyConnectionsOnce() {
        CoreHostStreamProperties properties = new CoreHostStreamProperties();
        properties.setInstanceId("core-a");
        properties.setDrainEnabled(true);
        properties.setDrainDeadlineAt(System.currentTimeMillis() - 1L);
        CoreLegacyStreamDrainRegistry registry = new CoreLegacyStreamDrainRegistry();
        ConnectionRegistry.ConnectionHandle handle = mock(ConnectionRegistry.ConnectionHandle.class);
        registry.register(handle);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CoreLegacyStreamDrainer drainer = new CoreLegacyStreamDrainer(
                properties, registry, meters);

        drainer.reconcileDeadline();
        drainer.reconcileDeadline();

        verify(handle).close(ConnectionRegistry.REASON_STREAM_MIGRATION);
        assertThat(meters.get("drain_connection_close_rate").counter().count()).isEqualTo(1D);
    }

    @Test
    void deadlineDoesNothingBeforeDeadlineOrWhenDrainIsDisabled() {
        CoreHostStreamProperties properties = new CoreHostStreamProperties();
        properties.setDrainDeadlineAt(System.currentTimeMillis() + 60_000L);
        CoreLegacyStreamDrainRegistry registry = new CoreLegacyStreamDrainRegistry();
        ConnectionRegistry.ConnectionHandle handle = mock(ConnectionRegistry.ConnectionHandle.class);
        registry.register(handle);
        CoreLegacyStreamDrainer drainer = new CoreLegacyStreamDrainer(
                properties, registry, new SimpleMeterRegistry());

        drainer.reconcileDeadline();
        properties.setDrainEnabled(true);
        drainer.reconcileDeadline();

        verify(handle, never()).close(ConnectionRegistry.REASON_STREAM_MIGRATION);
    }
}
