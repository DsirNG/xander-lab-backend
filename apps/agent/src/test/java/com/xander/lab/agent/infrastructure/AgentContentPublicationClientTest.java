package com.xander.lab.agent.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentContentPublicationClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void forwardsExplicitIdsAndTokenToContentLedger() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/agent/publications/99/sync/csdn", exchange -> {
            assertEquals("content-secret", exchange.getRequestHeaders()
                    .getFirst("X-DinQor-Internal-Token"));
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(request.contains("\"userId\":42"));
            assertTrue(request.contains("\"postId\":99"));
            byte[] response = "{\"platform\":\"csdn\",\"externalId\":\"csdn-1\",\"url\":\"https://csdn.example/1\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        AgentContentPublicationClient client = new AgentContentPublicationClient(
                RestClient.builder(), properties(), new ObjectMapper());

        assertThat(client.sync(42L, 99L, "csdn").externalId()).isEqualTo("csdn-1");
    }

    @Test
    void treatsEmptyFindSucceededResponseAsNoPriorSuccess() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/agent/publications/99/find-succeeded/juejin", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();

        AgentContentPublicationClient client = new AgentContentPublicationClient(
                RestClient.builder(), properties(), new ObjectMapper());

        assertThat(client.findSucceeded(42L, 99L, "juejin")).isNull();
    }

    private AgentContentClientProperties properties() {
        AgentContentClientProperties properties = new AgentContentClientProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        properties.setInternalToken("content-secret");
        return properties;
    }
}
