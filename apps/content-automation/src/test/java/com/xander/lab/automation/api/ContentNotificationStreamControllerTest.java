package com.xander.lab.automation.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.automation.host.ContentAutomationStreamState;
import com.xander.lab.automation.infrastructure.ContentNotificationStreamService;
import com.xander.lab.common.security.ConnectionRegistry;
import com.xander.lab.common.security.SecurityStateCache;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContentNotificationStreamControllerTest {

    @Test
    void disabledAccountClosesContentNotificationSse() {
        SecurityStateCache cache = new SecurityStateCache();
        ConnectionRegistry registry = new ConnectionRegistry();
        ContentAutomationStreamState streamState = new ContentAutomationStreamState();
        ContentNotificationStreamService service = new ContentNotificationStreamService(
                new ObjectMapper(), cache, registry, streamState);

        service.subscribe(42L);
        assertThat(streamState.activeSse()).isOne();
        cache.markDisabled(42L, 1L);

        assertThat(registry.closeAll(42L, ConnectionRegistry.REASON_ACCOUNT_DISABLED)).isOne();
        assertThat(streamState.activeSse()).isZero();
    }
}
