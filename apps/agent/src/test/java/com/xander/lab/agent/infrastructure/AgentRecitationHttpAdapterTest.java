package com.xander.lab.agent.infrastructure;

import com.xander.lab.recitation.spi.MaterialFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AgentRecitationHttpAdapterTest {

    @Test
    void forwardsMaterialFiltersAndDeserializesTheList() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://core-host:30002/internal/agent/recitation/material/search"))
                .andExpect(header("X-DinQor-Internal-Token", "sync-secret"))
                .andExpect(content().json(
                        "{\"userId\":42,\"filter\":{\"search\":null,\"masteryLevel\":null,\"dueOnly\":false,\"limit\":2147483647}}"))
                .andRespond(withSuccess("{\"materials\":[]}", MediaType.APPLICATION_JSON));

        AgentRecitationHttpAdapter adapter = new AgentRecitationHttpAdapter(builder, properties());

        assertThat(adapter.searchMaterials(42L, MaterialFilter.all())).isEmpty();
        server.verify();
    }

    @Test
    void preservesRemoteNotFoundForMaterialLookup() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://core-host:30002/internal/agent/recitation/material/get"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        AgentRecitationHttpAdapter adapter = new AgentRecitationHttpAdapter(builder, properties());

        assertThatThrownBy(() -> adapter.getMaterial(42L, 7L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
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
