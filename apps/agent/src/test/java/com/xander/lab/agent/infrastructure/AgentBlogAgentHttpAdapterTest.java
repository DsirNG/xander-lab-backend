package com.xander.lab.agent.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xander.lab.blogagent.spi.BlogAgentPostView;
import com.xander.lab.blogagent.spi.BlogAgentTaskDraft;
import com.xander.lab.blogagent.spi.BlogAgentTaskView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentBlogAgentHttpAdapterTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void mapsTaskAndPostBoundariesAndStreamsCompletion() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/agent/blogagent/create", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-DinQor-Internal-Token"))
                    .isEqualTo("content-token");
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).contains("\"userId\":7", "\"clientRequestId\":\"request-1\"");
            respond(exchange, 200, "{\"id\":9,\"title\":\"draft\",\"summary\":\"summary\"}");
        });
        server.createContext("/internal/agent/blogagent/find-ready", exchange ->
                respond(exchange, 200, "{\"id\":9,\"title\":\"ready\",\"publishedPostId\":88}"));
        server.createContext("/internal/agent/blogagent/find-published", exchange ->
                respond(exchange, 200, "{\"id\":88,\"title\":\"post\",\"status\":1,"
                        + "\"csdnSynced\":true,\"juejinSynced\":false}"));
        server.createContext("/internal/agent/blogagent/88/publish", exchange ->
                respond(exchange, 200, "{\"id\":88,\"title\":\"post\",\"status\":1}"));
        server.createContext("/internal/agent/blogagent/9/run/stream", exchange -> {
            byte[] response = ("event: stage\ndata: analyze|正在分析\n\n"
                    + "event: complete\ndata: {\"task\":{\"id\":9,\"title\":\"ready\","
                    + "\"summary\":\"summary\"}}\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        AgentContentClientProperties properties = new AgentContentClientProperties();
        properties.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        properties.setInternalToken("content-token");
        AgentBlogAgentHttpAdapter adapter = new AgentBlogAgentHttpAdapter(
                RestClient.builder(), properties, new ObjectMapper());

        BlogAgentTaskView created = adapter.create(7L, new BlogAgentTaskDraft("topic", "request-1"));
        BlogAgentTaskView ready = adapter.findReadyTaskByClientRequestId(7L, "request-1");
        BlogAgentPostView published = adapter.findPublishedPost(7L, 9L);
        BlogAgentPostView post = adapter.publish(7L, 88L);
        List<String> events = new ArrayList<>();
        BlogAgentTaskView completed = adapter.runStream(7L, 9L,
                (event, data) -> events.add(event + ":" + data), () -> false);

        assertThat(created).extracting(BlogAgentTaskView::id, BlogAgentTaskView::title)
                .containsExactly(9L, "draft");
        assertThat(ready.publishedPostId()).isEqualTo(88L);
        assertThat(published).isEqualTo(new BlogAgentPostView(88L, "post", 1, true, false));
        assertThat(post.id()).isEqualTo(88L);
        assertThat(completed.title()).isEqualTo("ready");
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).startsWith("stage:analyze|");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange,
                                int status, String body) throws java.io.IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
