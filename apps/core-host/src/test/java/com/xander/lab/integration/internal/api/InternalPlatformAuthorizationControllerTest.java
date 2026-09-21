package com.xander.lab.integration.internal.api;

import com.xander.lab.integration.internal.application.IntegrationInternalApiProperties;
import com.xander.lab.integration.mcp.csdn.application.CsdnAuthorizationService;
import com.xander.lab.integration.mcp.juejin.application.JuejinAuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalPlatformAuthorizationControllerTest {

    @Test
    void mapsCsdnAuthorizationViewWithoutExposingCredentialMaterial() {
        CsdnAuthorizationService csdn = mock(CsdnAuthorizationService.class);
        JuejinAuthorizationService juejin = mock(JuejinAuthorizationService.class);
        when(csdn.start(7L)).thenReturn(new CsdnAuthorizationService.AuthorizationView(
                "PENDING", "data:image/png;base64,qr", 30, 2, 8));

        InternalPlatformAuthorizationController controller = controller(csdn, juejin);

        var response = controller.csdnStart("core-token", 7L);

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.qrCodeDataUrl()).isEqualTo("data:image/png;base64,qr");
        assertThat(response.queuePosition()).isEqualTo(2);
        verify(csdn).start(7L);
    }

    @Test
    void executesCancelAndDisconnectForTheExplicitJuejinOwner() {
        CsdnAuthorizationService csdn = mock(CsdnAuthorizationService.class);
        JuejinAuthorizationService juejin = mock(JuejinAuthorizationService.class);
        InternalPlatformAuthorizationController controller = controller(csdn, juejin);

        controller.juejinCancel("core-token", 9L);
        controller.juejinDisconnect("core-token", 9L);

        verify(juejin).cancel(9L);
        verify(juejin).disconnect(9L);
    }

    @Test
    void rejectsWrongInternalTokenBeforeCallingAuthorizationService() {
        CsdnAuthorizationService csdn = mock(CsdnAuthorizationService.class);
        InternalPlatformAuthorizationController controller = controller(csdn,
                mock(JuejinAuthorizationService.class));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.csdnStart("wrong-token", 7L));

        assertThat(exception.getStatusCode().value()).isEqualTo(404);
    }

    private InternalPlatformAuthorizationController controller(
            CsdnAuthorizationService csdn, JuejinAuthorizationService juejin) {
        IntegrationInternalApiProperties properties = new IntegrationInternalApiProperties();
        properties.setInternalToken("core-token");
        return new InternalPlatformAuthorizationController(csdn, juejin, properties);
    }
}
