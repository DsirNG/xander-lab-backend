package com.xander.lab.integration.host;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PlatformJuejinCatalogControllerTest {

    @Test
    void ownsCatalogSyncBoundaryAndPassesTrustedUser() {
        PlatformIntegrationInternalCatalogController bridge =
                mock(PlatformIntegrationInternalCatalogController.class);
        var response = new PlatformJuejinCatalogController(bridge, properties()).sync("7");

        assertThat(response.getCode()).isEqualTo(200);
        verify(bridge).sync("platform-token", 7L);
    }

    @Test
    void rejectsMissingIdentityBeforeCallingCatalogBridge() {
        PlatformIntegrationInternalCatalogController bridge =
                mock(PlatformIntegrationInternalCatalogController.class);
        var controller = new PlatformJuejinCatalogController(bridge, properties());

        assertThat(controller.sync(null).getCode()).isEqualTo(401);
        assertThat(controller.sync("bad-id").getCode()).isEqualTo(401);
    }

    private PlatformIntegrationProperties properties() {
        PlatformIntegrationProperties properties = new PlatformIntegrationProperties();
        properties.setInternalToken("platform-token");
        return properties;
    }
}
