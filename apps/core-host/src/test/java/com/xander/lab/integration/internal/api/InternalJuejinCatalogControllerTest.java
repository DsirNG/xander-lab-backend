package com.xander.lab.integration.internal.api;

import com.xander.lab.integration.internal.application.IntegrationInternalApiProperties;
import com.xander.lab.integration.mcp.juejin.application.JuejinCatalogService;
import com.xander.lab.integration.mcp.juejin.application.JuejinCredentialService;
import com.xander.lab.integration.mcp.juejin.infrastructure.JuejinClient;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalJuejinCatalogControllerTest {

    @Test
    void resolvesCredentialForTheExplicitOwnerBeforeRefreshingCatalog() {
        JuejinCatalogService catalog = mock(JuejinCatalogService.class);
        JuejinCredentialService credentials = mock(JuejinCredentialService.class);
        JuejinClient.Credential credential = new JuejinClient.Credential("cookie", "csrf", "aid", "uuid");
        when(credentials.requireCredential(7L)).thenReturn(credential);
        InternalJuejinCatalogController controller = controller(catalog, credentials);

        controller.sync("core-token", 7L);

        verify(credentials).requireCredential(7L);
        verify(catalog).sync(credential);
    }

    @Test
    void rejectsWrongInternalTokenBeforeReadingCredential() {
        JuejinCredentialService credentials = mock(JuejinCredentialService.class);
        InternalJuejinCatalogController controller = controller(mock(JuejinCatalogService.class), credentials);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.sync("wrong-token", 7L));

        assertThat(exception.getStatusCode().value()).isEqualTo(404);
    }

    private InternalJuejinCatalogController controller(
            JuejinCatalogService catalog, JuejinCredentialService credentials) {
        IntegrationInternalApiProperties properties = new IntegrationInternalApiProperties();
        properties.setInternalToken("core-token");
        return new InternalJuejinCatalogController(catalog, credentials, properties);
    }
}
