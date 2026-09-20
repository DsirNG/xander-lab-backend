package com.xander.lab.agent.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AgentAccessTokenHttpAdapterTest {

    @Test
    void sendsTheAccessTokenOnlyInTheRequestBodyAndReturnsTheResolvedUser() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://core-host:30002/internal/agent/auth/resolve"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-DinQor-Internal-Token", "sync-secret"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"accessToken\":\"access-token\"}"))
                .andRespond(withSuccess("{\"userId\":42}", MediaType.APPLICATION_JSON));

        AgentAccessTokenHttpAdapter adapter = new AgentAccessTokenHttpAdapter(
                builder, properties());

        assertThat(adapter.resolveUserId("access-token")).isEqualTo(42L);
        server.verify();
    }

    @Test
    void rejectsWhenCoreHostIsUnavailable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://core-host:30002/internal/agent/auth/resolve"))
                .andRespond(withServerError());

        AgentAccessTokenHttpAdapter adapter = new AgentAccessTokenHttpAdapter(
                builder, properties());

        assertThat(adapter.resolveUserId("access-token")).isNull();
        server.verify();
    }

    @Test
    void doesNotCallCoreForAnEmptyAccessToken() {
        AgentAccessTokenHttpAdapter adapter = new AgentAccessTokenHttpAdapter(
                RestClient.builder(), properties());

        assertThat(adapter.resolveUserId(" ")).isNull();
    }

    private AgentCoreClientProperties properties() {
        AgentCoreClientProperties properties = new AgentCoreClientProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://core-host:30002");
        properties.setInternalToken("sync-secret");
        return properties;
    }
}
