package com.xander.lab.automation.infrastructure;

import com.xander.lab.automation.host.ContentSecurityStateProperties;
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

class ContentSecurityStateReconcilerTest {

    @Test
    void bootstrapLoadsSnapshotBeforeDeclaringSecurityPlaneReady() {
        ContentSecurityStateClient client = mock(ContentSecurityStateClient.class);
        when(client.snapshot()).thenReturn(new SecurityStateSyncApi.Snapshot(
                List.of(new SecurityStateSyncApi.Entry(42L, true, 7L, 10L)), 10L));
        when(client.changes(10L, 500)).thenReturn(new SecurityStateSyncApi.Changes(List.of(), 10L));

        SecurityStateCache cache = new SecurityStateCache();
        SecurityPlaneReadiness readiness = new SecurityPlaneReadiness();
        ContentSecurityStateReconciler reconciler = new ContentSecurityStateReconciler(
                client, properties(), cache, new ConnectionRegistry(), readiness);

        reconciler.run(null);

        assertThat(readiness.isReady()).isTrue();
        assertThat(cache.isDisabled(42L)).isTrue();
        assertThat(cache.lastSeenSeq()).isEqualTo(10L);
    }

    @Test
    void incrementalDisableClosesConnectionsOnThisInstance() {
        ContentSecurityStateClient client = mock(ContentSecurityStateClient.class);
        SecurityStateCache cache = new SecurityStateCache();
        ConnectionRegistry registry = new ConnectionRegistry();
        SecurityPlaneReadiness readiness = new SecurityPlaneReadiness();
        ContentSecurityStateReconciler reconciler = new ContentSecurityStateReconciler(
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
                return ConnectionRegistry.KIND_SSE;
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

    @Test
    void emptyIncrementalBatchAdvancesWatermarkWithoutReportingFailure() {
        ContentSecurityStateClient client = mock(ContentSecurityStateClient.class);
        SecurityStateCache cache = new SecurityStateCache();
        SecurityPlaneReadiness readiness = new SecurityPlaneReadiness();
        ContentSecurityStateReconciler reconciler = new ContentSecurityStateReconciler(
                client, properties(), cache, new ConnectionRegistry(), readiness);
        when(client.snapshot()).thenReturn(new SecurityStateSyncApi.Snapshot(List.of(), 0L));
        when(client.changes(0L, 500)).thenReturn(new SecurityStateSyncApi.Changes(List.of(), 0L));
        reconciler.run(null);

        when(client.changes(0L, 500)).thenReturn(new SecurityStateSyncApi.Changes(List.of(), 7L));
        reconciler.reconcile();

        assertThat(cache.lastSeenSeq()).isEqualTo(7L);
        assertThat(readiness.isReady()).isTrue();
    }

    private static ContentSecurityStateProperties properties() {
        ContentSecurityStateProperties properties = new ContentSecurityStateProperties();
        properties.setReconcileEnabled(true);
        return properties;
    }
}
