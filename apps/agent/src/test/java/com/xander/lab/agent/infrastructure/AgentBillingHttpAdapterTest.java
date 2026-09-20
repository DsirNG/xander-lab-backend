package com.xander.lab.agent.infrastructure;

import com.xander.lab.payment.api.TokenUsage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class AgentBillingHttpAdapterTest {

    @Test
    void forwardsBillingCallsAcrossTheInternalBoundary() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://core-host:30002/internal/agent/billing/charge-tokens"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-DinQor-Internal-Token", "sync-secret"))
                .andExpect(content().json("{\"userId\":42,\"bizType\":\"agent-turn\",\"bizKey\":\"run:1\",\"inputTokens\":11,\"cachedTokens\":3,\"outputTokens\":7}"))
                .andRespond(withSuccess());
        server.expect(requestTo("http://core-host:30002/internal/agent/billing/flat-points"))
                .andRespond(withSuccess("{\"points\":17}", MediaType.APPLICATION_JSON));

        AgentBillingHttpAdapter adapter = new AgentBillingHttpAdapter(builder, properties());
        adapter.chargeTokens(42L, "agent-turn", "run:1", "run:1",
                new TokenUsage(11, 3, 7), "model", "turn");

        assertThat(adapter.flatPoints("agent-turn")).isEqualTo(17L);
        server.verify();
    }

    @Test
    void preservesThePaymentRequiredContractWhenBalanceIsTooLow() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://core-host:30002/internal/agent/billing/ensure-available"))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED));

        AgentBillingHttpAdapter adapter = new AgentBillingHttpAdapter(builder, properties());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.ensureAvailable(42L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("402 PAYMENT_REQUIRED");
        server.verify();
    }

    private AgentCoreClientProperties properties() {
        AgentCoreClientProperties properties = new AgentCoreClientProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://core-host:30002");
        properties.setInternalToken("sync-secret");
        return properties;
    }
}
