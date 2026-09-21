package com.xander.lab.agent.infrastructure;

import com.xander.lab.content.api.ContentPostQuery;
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

class AgentContentPostHttpAdapterTest {

    @Test
    void forwardsQueryAndDeserializesTheContentPage() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://content-automation:30006/internal/agent/content/list-my-posts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-DinQor-Internal-Token", "sync-secret"))
                .andExpect(content().json(
                        "{\"userId\":42,\"query\":{\"status\":null,\"search\":null,\"juejinSynced\":null,\"csdnSynced\":null},\"page\":1,\"size\":10}"))
                .andRespond(withSuccess(
                        "{\"records\":[],\"total\":0,\"current\":1,\"pages\":0,\"size\":10,\"hasMore\":false}",
                        MediaType.APPLICATION_JSON));

        AgentContentPostHttpAdapter adapter = new AgentContentPostHttpAdapter(builder, properties());

        assertThat(adapter.listMyPosts(42L, ContentPostQuery.NONE, 1, 10).total()).isZero();
        server.verify();
    }

    @Test
    void forwardsTheExplicitPublishFlag() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://content-automation:30006/internal/agent/content/move-to-trash"))
                .andExpect(header("X-DinQor-Internal-Token", "sync-secret"))
                .andExpect(content().json("{\"userId\":42,\"postId\":99}"))
                .andRespond(withSuccess());

        AgentContentPostHttpAdapter adapter = new AgentContentPostHttpAdapter(builder, properties());
        adapter.moveToTrash(42L, 99L);
        server.verify();
    }

    private AgentContentClientProperties properties() {
        AgentContentClientProperties properties = new AgentContentClientProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://content-automation:30006");
        properties.setInternalToken("sync-secret");
        return properties;
    }
}
