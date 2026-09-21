package com.xander.lab.integration.host;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformIntegrationInternalCredentialControllerTest {

    @Test
    void readsLocalCredentialStatusWhenPlatformOwnsTheStore() {
        PlatformCredentialStatusStore store = mock(PlatformCredentialStatusStore.class);
        when(store.isAuthorized("csdn", 7L)).thenReturn(true);

        PlatformIntegrationInternalCredentialController controller = controller(Optional.of(store), "");

        var response = controller.csdnStatus("platform-token", 7L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("{\"authorized\":true}", new String(response.getBody()));
        verify(store).isAuthorized("csdn", 7L);
    }

    @Test
    void rejectsInvalidOwnerBeforeReadingLocalCredentialStatus() {
        PlatformCredentialStatusStore store = mock(PlatformCredentialStatusStore.class);
        PlatformIntegrationInternalCredentialController controller = controller(Optional.of(store));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.juejinStatus("platform-token", 0L));

        assertEquals(400, exception.getStatusCode().value());
    }

    private PlatformIntegrationInternalCredentialController controller(
            Optional<PlatformCredentialStatusStore> store) {
        return controller(store, "core-token");
    }

    private PlatformIntegrationInternalCredentialController controller(
            Optional<PlatformCredentialStatusStore> store, String coreToken) {
        PlatformIntegrationProperties properties = new PlatformIntegrationProperties();
        properties.setInternalToken("platform-token");
        properties.setCoreInternalToken(coreToken);
        return new PlatformIntegrationInternalCredentialController(
                RestClient.builder(), properties, store);
    }
}
