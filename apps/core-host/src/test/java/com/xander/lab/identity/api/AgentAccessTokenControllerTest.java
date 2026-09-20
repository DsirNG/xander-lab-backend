package com.xander.lab.identity.api;

import com.xander.lab.identity.application.AuthService;
import com.xander.lab.identity.application.SecurityStateSyncProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentAccessTokenControllerTest {

    @Test
    void resolvesUserIdForAnAuthorizedAgentRequest() {
        AuthService authService = mock(AuthService.class);
        when(authService.resolveUserId("access-token")).thenReturn(42L);

        AgentAccessTokenController controller = new AgentAccessTokenController(authService, properties());

        assertThat(controller.resolve("sync-secret",
                new AgentAccessTokenController.ResolveRequest("access-token")).userId())
                .isEqualTo(42L);
        verify(authService).resolveUserId("access-token");
    }

    @Test
    void hidesTheEndpointFromRequestsWithoutTheInternalToken() {
        AuthService authService = mock(AuthService.class);
        AgentAccessTokenController controller = new AgentAccessTokenController(authService, properties());

        assertThatThrownBy(() -> controller.resolve("wrong-secret",
                new AgentAccessTokenController.ResolveRequest("access-token")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
        verifyNoInteractions(authService);
    }

    private SecurityStateSyncProperties properties() {
        SecurityStateSyncProperties properties = new SecurityStateSyncProperties();
        properties.setInternalApiEnabled(true);
        properties.setInternalToken("sync-secret");
        return properties;
    }
}
