package com.xander.lab.integration.internal.api;

import com.xander.lab.integration.api.BlogSyncResult;
import com.xander.lab.integration.internal.application.IntegrationInternalApiProperties;
import com.xander.lab.integration.mcp.csdn.application.CsdnBlogSyncService;
import com.xander.lab.integration.mcp.juejin.application.JuejinBlogSyncService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalBlogSyncControllerTest {

    @Test
    void passesExplicitOwnerAndPostToTheOwningSyncService() {
        CsdnBlogSyncService csdn = mock(CsdnBlogSyncService.class);
        JuejinBlogSyncService juejin = mock(JuejinBlogSyncService.class);
        IntegrationInternalApiProperties properties = new IntegrationInternalApiProperties();
        properties.setInternalToken("core-token");
        InternalBlogSyncController controller = new InternalBlogSyncController(csdn, juejin, properties);
        when(juejin.sync(7L, 42L, null, List.of()))
                .thenReturn(new BlogSyncResult("juejin", "j-42", "https://juejin.example/42"));

        BlogSyncResult result = controller.syncJuejin("core-token", 42L,
                new InternalBlogSyncController.JuejinSyncRequest(7L, 42L, null, List.of()));

        assertEquals("j-42", result.externalId());
        verify(juejin).sync(7L, 42L, null, List.of());
    }
}
