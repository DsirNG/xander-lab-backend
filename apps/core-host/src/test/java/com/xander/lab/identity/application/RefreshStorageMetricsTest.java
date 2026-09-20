package com.xander.lab.identity.application;

import com.xander.lab.identity.infrastructure.AuthRefreshTokenHistoryMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RefreshStorageMetricsTest {

    @Test
    void observesRefreshSessionAndHistoryRows() {
        AuthRefreshTokenHistoryMapper mapper = mock(AuthRefreshTokenHistoryMapper.class);
        when(mapper.countSessions()).thenReturn(12);
        when(mapper.countAll()).thenReturn(48);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RefreshStorageMetrics metrics = new RefreshStorageMetrics(registry, mapper);

        metrics.refresh();

        assertThat(registry.get("refresh_session_rows").gauge().value()).isEqualTo(12D);
        assertThat(registry.get("refresh_history_rows").gauge().value()).isEqualTo(48D);
    }

    @Test
    void keepsLastValuesWhenObservationFails() {
        AuthRefreshTokenHistoryMapper mapper = mock(AuthRefreshTokenHistoryMapper.class);
        when(mapper.countSessions()).thenReturn(12).thenThrow(new IllegalStateException("db down"));
        when(mapper.countAll()).thenReturn(48);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RefreshStorageMetrics metrics = new RefreshStorageMetrics(registry, mapper);

        metrics.refresh();
        metrics.refresh();

        assertThat(registry.get("refresh_session_rows").gauge().value()).isEqualTo(12D);
        assertThat(registry.get("refresh_history_rows").gauge().value()).isEqualTo(48D);
    }
}
