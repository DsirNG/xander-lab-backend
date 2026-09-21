package com.xander.lab.agent.infrastructure;

import com.xander.lab.agent.host.AgentSecurityStateProperties;
import com.xander.lab.common.security.ConnectionRegistry;
import com.xander.lab.common.security.SecurityPlaneReadiness;
import com.xander.lab.common.security.SecurityStateCache;
import com.xander.lab.core.api.SecurityStateSyncApi;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentSecurityStateReconcilerTest {

    @Test
    void bootstrapLoadsSnapshotBeforeDeclaringSecurityPlaneReady() {
        AgentSecurityStateClient client = mock(AgentSecurityStateClient.class);
        when(client.snapshot()).thenReturn(new SecurityStateSyncApi.Snapshot(
                List.of(new SecurityStateSyncApi.Entry(42L, true, 7L, 10L)), 10L));
        when(client.changes(10L, 500)).thenReturn(new SecurityStateSyncApi.Changes(List.of(), 10L));

        SecurityStateCache cache = new SecurityStateCache();
        SecurityPlaneReadiness readiness = new SecurityPlaneReadiness();
        AgentSecurityStateReconciler reconciler = new AgentSecurityStateReconciler(
                client, properties(), cache, new ConnectionRegistry(), readiness);

        reconciler.run(null);

        assertThat(readiness.isReady()).isTrue();
        assertThat(cache.isDisabled(42L)).isTrue();
        assertThat(cache.lastSeenSeq()).isEqualTo(10L);
    }

    @Test
    void incrementalDisableClosesConnectionsOnThisInstance() {
        AgentSecurityStateClient client = mock(AgentSecurityStateClient.class);
        SecurityStateCache cache = new SecurityStateCache();
        ConnectionRegistry registry = new ConnectionRegistry();
        SecurityPlaneReadiness readiness = new SecurityPlaneReadiness();
        AgentSecurityStateReconciler reconciler = new AgentSecurityStateReconciler(
                client, properties(), cache, registry, readiness);
        when(client.snapshot()).thenReturn(new SecurityStateSyncApi.Snapshot(List.of(), 0L));
        when(client.changes(0L, 500)).thenReturn(new SecurityStateSyncApi.Changes(List.of(), 0L));
        reconciler.run(null);
        AtomicBoolean closed = new AtomicBoolean();
        registry.register(new ConnectionRegistry.ConnectionHandle() {
            @Override
            public Long accountId() {
                return 42L;
            }

            @Override
            public String kind() {
                return ConnectionRegistry.KIND_WS;
            }

            @Override
            public void close(String reason) {
                closed.set(ConnectionRegistry.REASON_ACCOUNT_DISABLED.equals(reason));
            }
        });
        when(client.changes(0L, 500)).thenReturn(new SecurityStateSyncApi.Changes(
                List.of(new SecurityStateSyncApi.Entry(42L, true, 3L, 1L)), 1L));

        reconciler.reconcile();

        assertThat(cache.isDisabled(42L)).isTrue();
        assertThat(cache.lastSeenSeq()).isEqualTo(1L);
        assertThat(registry.activeCount(42L)).isZero();
        assertThat(closed).isTrue();
    }

    private static AgentSecurityStateProperties properties() {
        AgentSecurityStateProperties properties = new AgentSecurityStateProperties();
        properties.setReconcileEnabled(true);
        return properties;
    }
}
