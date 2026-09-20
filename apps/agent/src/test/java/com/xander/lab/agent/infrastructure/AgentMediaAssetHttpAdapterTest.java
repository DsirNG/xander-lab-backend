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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AgentMediaAssetHttpAdapterTest {

    @Test
    void forwardsBinaryImageContentAsBase64AndReadsTheAsset() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://core-host:30002/internal/agent/media/upload-image"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-DinQor-Internal-Token", "sync-secret"))
                .andExpect(content().json(
                        "{\"userId\":42,\"content\":\"AQI=\",\"originalName\":\"image.png\",\"contentType\":\"image/png\"}"))
                .andRespond(withSuccess(
                        "{\"id\":7,\"userId\":42,\"url\":\"https://cdn/image.png\",\"originalName\":\"image.png\","
                                + "\"storedName\":\"stored.png\",\"size\":2,\"contentType\":\"image/png\","
                                + "\"width\":1,\"height\":1,\"sourceType\":\"user_upload\",\"agentTaskId\":null,"
                                + "\"generationMeta\":null,\"createdAt\":null}", MediaType.APPLICATION_JSON));

        AgentMediaAssetHttpAdapter adapter = new AgentMediaAssetHttpAdapter(builder, properties());

        assertThat(adapter.uploadImage(42L, new byte[]{1, 2}, "image.png", "image/png").id()).isEqualTo(7L);
        server.verify();
    }

    @Test
    void normalizesAnEmptyTaskResponseToAnEmptyList() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://core-host:30002/internal/agent/media/list-by-task"))
                .andRespond(withSuccess("{\"assets\":[]}", MediaType.APPLICATION_JSON));

        AgentMediaAssetHttpAdapter adapter = new AgentMediaAssetHttpAdapter(builder, properties());

        assertThat(adapter.listByTask(42L, 7L)).isEmpty();
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
