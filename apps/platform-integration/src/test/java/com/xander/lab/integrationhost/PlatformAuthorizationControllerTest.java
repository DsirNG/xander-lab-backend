package com.xander.lab.integration.host;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformAuthorizationControllerTest {

    @Test
    void ownsStartBoundaryAndPassesTrustedUserToCoreBridge() {
        PlatformIntegrationInternalAuthorizationController bridge =
                mock(PlatformIntegrationInternalAuthorizationController.class);
        PlatformIntegrationProperties properties = properties();
        var expected = new PlatformIntegrationInternalAuthorizationController.AuthorizationResponse(
                "PENDING", "qr", 30, null, null);
        when(bridge.start("platform-token", 7L, "csdn")).thenReturn(expected);

        var response = new PlatformAuthorizationController(bridge, properties)
                .start("csdn", "7");

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getData()).isEqualTo(expected);
        verify(bridge).start("platform-token", 7L, "csdn");
    }

    @Test
    void forwardsCancelAndDisconnectWithoutChangingTheUserOwner() {
        PlatformIntegrationInternalAuthorizationController bridge =
                mock(PlatformIntegrationInternalAuthorizationController.class);
        var controller = new PlatformAuthorizationController(bridge, properties());

        assertThat(controller.cancel("juejin", "9").getCode()).isEqualTo(200);
        assertThat(controller.disconnect("juejin", "9").getCode()).isEqualTo(200);

        verify(bridge).cancel("platform-token", 9L, "juejin");
        verify(bridge).disconnect("platform-token", 9L, "juejin");
    }

    @Test
    void rejectsMissingIdentityBeforeCallingCoreBridge() {
        PlatformIntegrationInternalAuthorizationController bridge =
                mock(PlatformIntegrationInternalAuthorizationController.class);
        var controller = new PlatformAuthorizationController(bridge, properties());

        assertThat(controller.start("csdn", null).getCode()).isEqualTo(401);
        assertThat(controller.cancel("csdn", "bad-id").getCode()).isEqualTo(401);
    }

    private PlatformIntegrationProperties properties() {
        PlatformIntegrationProperties properties = new PlatformIntegrationProperties();
        properties.setInternalToken("platform-token");
        return properties;
    }
}
