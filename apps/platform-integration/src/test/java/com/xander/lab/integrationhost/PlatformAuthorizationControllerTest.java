package com.xander.lab.integration.host;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    @Test
    void usesPlatformOwnedCsdnAuthorizationWhenTheStagedOwnerIsPresent() {
        PlatformIntegrationInternalAuthorizationController bridge =
                mock(PlatformIntegrationInternalAuthorizationController.class);
        PlatformCsdnAuthorizationService local = mock(PlatformCsdnAuthorizationService.class);
        PlatformCsdnAuthorizationService.AuthorizationView expected =
                new PlatformCsdnAuthorizationService.AuthorizationView(
                        "PENDING", "qr", 35, null, null);
        when(local.start(7L)).thenReturn(expected);

        var controller = new PlatformAuthorizationController(bridge, properties(), Optional.of(local));
        var response = controller.start("csdn", "7");

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getData()).isEqualTo(expected);
        verify(local).start(7L);
        verify(bridge, never()).start("platform-token", 7L, "csdn");
    }

    @Test
    void localCsdnCancelAndDisconnectDoNotCallTheCoreBridge() {
        PlatformIntegrationInternalAuthorizationController bridge =
                mock(PlatformIntegrationInternalAuthorizationController.class);
        PlatformCsdnAuthorizationService local = mock(PlatformCsdnAuthorizationService.class);
        var controller = new PlatformAuthorizationController(bridge, properties(), Optional.of(local));

        assertThat(controller.cancel("csdn", "7").getCode()).isEqualTo(200);
        assertThat(controller.disconnect("csdn", "7").getCode()).isEqualTo(200);

        verify(local).cancel(7L);
        verify(local).disconnect(7L);
        verify(bridge, never()).cancel("platform-token", 7L, "csdn");
        verify(bridge, never()).disconnect("platform-token", 7L, "csdn");
    }

    private PlatformIntegrationProperties properties() {
        PlatformIntegrationProperties properties = new PlatformIntegrationProperties();
        properties.setInternalToken("platform-token");
        return properties;
    }
}
