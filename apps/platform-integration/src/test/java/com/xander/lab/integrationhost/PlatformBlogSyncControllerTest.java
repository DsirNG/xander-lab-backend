package com.xander.lab.integration.host;

import com.xander.lab.integration.api.BlogSyncResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformBlogSyncControllerTest {

    @Test
    void ownsCsdnPublicBoundaryAndPassesExplicitCallerToBridge() {
        PlatformIntegrationInternalSyncController bridge = mock(PlatformIntegrationInternalSyncController.class);
        PlatformIntegrationProperties properties = properties();
        BlogSyncResult expected = new BlogSyncResult("csdn", "ext-42", "https://csdn.example/42");
        when(bridge.syncCsdn("platform-token", 42L,
                new PlatformIntegrationInternalSyncController.CsdnSyncRequest(7L, 42L)))
                .thenReturn(expected);

        var response = new PlatformBlogSyncController(bridge, properties).syncCsdn(42L, "7");

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getData()).isEqualTo(expected);
        verify(bridge).syncCsdn("platform-token", 42L,
                new PlatformIntegrationInternalSyncController.CsdnSyncRequest(7L, 42L));
    }

    @Test
    void preservesJuejinOptionalPublishMetadata() {
        PlatformIntegrationInternalSyncController bridge = mock(PlatformIntegrationInternalSyncController.class);
        PlatformIntegrationProperties properties = properties();
        BlogSyncResult expected = new BlogSyncResult("juejin", "ext-9", "https://juejin.example/9");
        var request = new PlatformIntegrationInternalSyncController.JuejinSyncRequest(
                7L, 9L, "category-1", List.of("tag-1", "tag-2"));
        when(bridge.syncJuejin("platform-token", 9L, request)).thenReturn(expected);

        var response = new PlatformBlogSyncController(bridge, properties).syncJuejin(
                9L, "7", Map.of("juejinCategoryId", "category-1",
                        "juejinTagIds", List.of("tag-1", "tag-2")));

        assertThat(response.getData()).isEqualTo(expected);
        verify(bridge).syncJuejin("platform-token", 9L, request);
    }

    @Test
    void rejectsMissingIdentityBeforeCallingVendorBridge() {
        PlatformIntegrationInternalSyncController bridge = mock(PlatformIntegrationInternalSyncController.class);
        var controller = new PlatformBlogSyncController(bridge, properties());

        assertThat(controller.syncCsdn(42L, null).getCode()).isEqualTo(401);
        assertThat(controller.syncJuejin(42L, "bad-id", null).getCode()).isEqualTo(401);
    }

    private PlatformIntegrationProperties properties() {
        PlatformIntegrationProperties properties = new PlatformIntegrationProperties();
        properties.setInternalToken("platform-token");
        return properties;
    }
}
