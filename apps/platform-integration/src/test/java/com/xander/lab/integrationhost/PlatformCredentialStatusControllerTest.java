package com.xander.lab.integration.host;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformCredentialStatusControllerTest {

    @Test
    void returnsCoreCompatibleAuthorizedStatusFromPlatformOwner() {
        PlatformCredentialStatusStore store = mock(PlatformCredentialStatusStore.class);
        when(store.status("csdn", 42L))
                .thenReturn(PlatformCredentialStatusStore.CredentialStatus.AUTHORIZED);

        var response = new PlatformCredentialStatusController(store).status("csdn", "42");

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getData().status()).isEqualTo("AUTHORIZED");
        assertThat(response.getData().qrCodeDataUrl()).isNull();
        verify(store).status("csdn", 42L);
    }

    @Test
    void preservesExpiredStateWithoutExposingCredentialMaterial() {
        PlatformCredentialStatusStore store = mock(PlatformCredentialStatusStore.class);
        when(store.status("juejin", 7L))
                .thenReturn(PlatformCredentialStatusStore.CredentialStatus.EXPIRED);

        var response = new PlatformCredentialStatusController(store).status("juejin", "7");

        assertThat(response.getData().status()).isEqualTo("EXPIRED");
        assertThat(response.getData().qrCodeDataUrl()).isNull();
    }

    @Test
    void rejectsMissingOrInvalidGatewayIdentity() {
        PlatformCredentialStatusStore store = mock(PlatformCredentialStatusStore.class);
        var controller = new PlatformCredentialStatusController(store);

        assertThat(controller.status("csdn", null).getCode()).isEqualTo(401);
        assertThat(controller.status("csdn", "0").getCode()).isEqualTo(401);
        assertThat(controller.status("csdn", "not-a-user").getCode()).isEqualTo(401);
    }
}
