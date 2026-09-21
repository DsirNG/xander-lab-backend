package com.xander.lab.blogagent.api;

import com.xander.lab.common.security.ConnectionRegistry;
import com.xander.lab.common.security.SecurityStateCache;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class BlogAgentStreamControllerTest {

    @Test
    void accountDisableBoundaryCanCloseContentSseHandle() {
        SecurityStateCache cache = new SecurityStateCache();
        ConnectionRegistry registry = new ConnectionRegistry();
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

        cache.markDisabled(42L, 1L);
        assertThat(cache.isDisabled(42L)).isTrue();
        assertThat(registry.closeAll(42L, ConnectionRegistry.REASON_ACCOUNT_DISABLED)).isOne();
        assertThat(closed.get()).isTrue();
    }
}
