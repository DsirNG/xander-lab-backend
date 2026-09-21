package com.xander.lab.common.security;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionRegistryTest {

    @Test
    void closeAllByReasonClosesEveryConnectionInThisInstance() {
        ConnectionRegistry registry = new ConnectionRegistry();
        AtomicReference<String> firstReason = new AtomicReference<>();
        AtomicReference<String> secondReason = new AtomicReference<>();
        registry.register(handle(1L, firstReason));
        registry.register(handle(2L, secondReason));

        assertThat(registry.closeAll(ConnectionRegistry.REASON_STREAM_MIGRATION)).isEqualTo(2);
        assertThat(firstReason).hasValue(ConnectionRegistry.REASON_STREAM_MIGRATION);
        assertThat(secondReason).hasValue(ConnectionRegistry.REASON_STREAM_MIGRATION);
        assertThat(registry.activeCount()).isZero();
    }

    private ConnectionRegistry.ConnectionHandle handle(Long accountId,
                                                        AtomicReference<String> reason) {
        return new ConnectionRegistry.ConnectionHandle() {
            @Override
            public Long accountId() {
                return accountId;
            }

            @Override
            public String kind() {
                return ConnectionRegistry.KIND_SSE;
            }

            @Override
            public void close(String closeReason) {
                reason.set(closeReason);
            }
        };
    }
}
