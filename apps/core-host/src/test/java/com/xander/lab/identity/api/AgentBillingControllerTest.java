package com.xander.lab.identity.api;

import com.xander.lab.identity.application.SecurityStateSyncProperties;
import com.xander.lab.payment.api.BillingPort;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentBillingControllerTest {

    @Test
    void forwardsAuthorizedTokenUsageAndReturnsBillingQueries() {
        BillingPort billing = mock(BillingPort.class);
        when(billing.flatPoints("agent-turn")).thenReturn(17L);
        when(billing.balance(42L)).thenReturn(900L);
        AgentBillingController controller = new AgentBillingController(billing, properties());

        controller.chargeTokens("sync-secret",
                new AgentBillingController.ChargeTokensRequest(42L, "agent-turn", "run:1", "run:1",
                        11, 3, 7, "model", "turn"));

        assertThat(controller.flatPoints("sync-secret",
                new AgentBillingController.BizTypeRequest("agent-turn")).points()).isEqualTo(17L);
        assertThat(controller.balance("sync-secret",
                new AgentBillingController.UserRequest(42L)).balance()).isEqualTo(900L);
        verify(billing).chargeTokens(org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq("agent-turn"), org.mockito.ArgumentMatchers.eq("run:1"),
                org.mockito.ArgumentMatchers.eq("run:1"), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("model"), org.mockito.ArgumentMatchers.eq("turn"));
    }

    @Test
    void hidesAllBillingOperationsWithoutTheInternalToken() {
        BillingPort billing = mock(BillingPort.class);
        AgentBillingController controller = new AgentBillingController(billing, properties());

        assertThatThrownBy(() -> controller.balance("wrong-secret",
                new AgentBillingController.UserRequest(42L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
        verifyNoInteractions(billing);
    }

    private SecurityStateSyncProperties properties() {
        SecurityStateSyncProperties properties = new SecurityStateSyncProperties();
        properties.setInternalApiEnabled(true);
        properties.setInternalToken("sync-secret");
        return properties;
    }
}
