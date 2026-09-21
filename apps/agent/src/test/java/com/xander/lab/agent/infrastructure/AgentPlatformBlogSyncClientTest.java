package com.xander.lab.agent.infrastructure;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPlatformBlogSyncClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void forwardsCsdnSyncWithExplicitOwnerAndToken() throws Exception {
        startServer("/internal/platform-integration/blog-posts/99/sync/csdn", 200,
                "{\"platform\":\"csdn\",\"externalId\":\"csdn-1\",\"url\":\"https://csdn.example/1\"}",
                exchange -> {
                    assertEquals("agent-secret", exchange.getRequestHeaders().getFirst("X-DinQor-Internal-Token"));
                    try {
                        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                        assertTrue(request.contains("\"userId\":42"));
                        assertTrue(request.contains("\"postId\":99"));
                    } catch (java.io.IOException exception) {
                        throw new AssertionError(exception);
                    }
                });

        AgentPlatformBlogSyncClient client = new AgentPlatformBlogSyncClient(RestClient.builder(),
                properties(), new com.fasterxml.jackson.databind.ObjectMapper());

        assertThat(client.syncCsdn(42L, 99L).externalId()).isEqualTo("csdn-1");
    }

    @Test
    void treatsEmptyFindSucceededResponseAsNoPriorSuccess() throws Exception {
        startServer("/internal/platform-integration/blog-posts/99/find-succeeded/juejin", 200, "", null);

        AgentPlatformBlogSyncClient client = new AgentPlatformBlogSyncClient(RestClient.builder(),
                properties(), new com.fasterxml.jackson.databind.ObjectMapper());

        assertThat(client.findSucceededJuejin(42L, 99L)).isNull();
    }

    @Test
    void mapsBadRequestToThePortArgumentContract() throws Exception {
        startServer("/internal/platform-integration/blog-posts/99/sync/csdn", 400, "{}", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
            } catch (java.io.IOException exception) {
                throw new AssertionError(exception);
            }
        });

        AgentPlatformBlogSyncClient client = new AgentPlatformBlogSyncClient(RestClient.builder(),
                properties(), new com.fasterxml.jackson.databind.ObjectMapper());

        assertThatThrownBy(() -> client.syncCsdn(42L, 99L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AgentPlatformClientProperties properties() {
        AgentPlatformClientProperties properties = new AgentPlatformClientProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        properties.setInternalToken("agent-secret");
        return properties;
    }

    private void startServer(String path, int status, String body,
                             java.util.function.Consumer<HttpExchange> assertion) throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(path, exchange -> {
            try {
                if (assertion != null) assertion.accept(exchange);
                byte[] response = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }
}
