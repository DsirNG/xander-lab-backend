package com.xander.lab.gateway;

import com.xander.lab.core.api.SecurityStateSyncApi;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityDenylistReconcilerGatewayTest {

    @Test
    void bootstrapsSnapshotAndReplaysChangesBeforeReady() throws Exception {
        GatewaySecurityStateProperties properties = new GatewaySecurityStateProperties();
        properties.setReconcileEnabled(true);
        GatewaySecurityPlaneReadiness readiness = new GatewaySecurityPlaneReadiness(properties);
        GatewaySecurityStateCache cache = new GatewaySecurityStateCache();
        GatewaySecurityStateClient client = mock(GatewaySecurityStateClient.class);
        when(client.snapshot()).thenReturn(new SecurityStateSyncApi.Snapshot(List.of(
                new SecurityStateSyncApi.Entry(42L, true, 2L, 2L),
                new SecurityStateSyncApi.Entry(43L, false, 3L, 3L)), 3L));
        when(client.changes(3L, 500)).thenReturn(new SecurityStateSyncApi.Changes(List.of(
                new SecurityStateSyncApi.Entry(44L, true, 1L, 4L)), 4L));

        SecurityDenylistReconcilerGateway reconciler = new SecurityDenylistReconcilerGateway(
                client, cache, properties, readiness);
        reconciler.run(null);

        assertThat(readiness.isReady()).isTrue();
        assertThat(cache.isDisabled(42L)).isTrue();
        assertThat(cache.isDisabled(43L)).isFalse();
        assertThat(cache.isDisabled(44L)).isTrue();
        assertThat(cache.lastSeenSeq()).isEqualTo(4L);
        assertThat(cache.sourceWatermark()).isEqualTo(4L);
        assertThat(cache.syncAgeMillis(System.currentTimeMillis())).isLessThan(1000L);
    }

    @Test
    void emptyIncrementalBatchAdvancesWatermarkWithoutReportingFailure() throws Exception {
        GatewaySecurityStateProperties properties = new GatewaySecurityStateProperties();
        properties.setReconcileEnabled(true);
        GatewaySecurityPlaneReadiness readiness = new GatewaySecurityPlaneReadiness(properties);
        GatewaySecurityStateCache cache = new GatewaySecurityStateCache();
        GatewaySecurityStateClient client = mock(GatewaySecurityStateClient.class);
        when(client.snapshot()).thenReturn(new SecurityStateSyncApi.Snapshot(List.of(), 0L));
        when(client.changes(0L, 500)).thenReturn(new SecurityStateSyncApi.Changes(List.of(), 0L));

        SecurityDenylistReconcilerGateway reconciler = new SecurityDenylistReconcilerGateway(
                client, cache, properties, readiness);
        reconciler.run(null);

        when(client.changes(0L, 500)).thenReturn(new SecurityStateSyncApi.Changes(List.of(), 7L));
        reconciler.reconcile();

        assertThat(cache.lastSeenSeq()).isEqualTo(7L);
        assertThat(cache.sourceWatermark()).isEqualTo(7L);
        assertThat(readiness.isReady()).isTrue();
    }
}
