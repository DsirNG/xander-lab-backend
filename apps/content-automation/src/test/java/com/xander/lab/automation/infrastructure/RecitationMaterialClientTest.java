package com.xander.lab.automation.infrastructure;

import com.xander.lab.automation.host.ContentAutomationProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies the internal recitation boundary used by background plan execution. */
class RecitationMaterialClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void readsMaterialWithExplicitUserAndInternalToken() throws Exception {
        StringBuilder requestBody = new StringBuilder();
        StringBuilder token = new StringBuilder();
        startServer(exchange -> {
            requestBody.append(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            token.append(exchange.getRequestHeaders().getFirst("X-DinQor-Internal-Token"));
            respond(exchange, 200, """
                    {"id":9,"title":"材料标题","content":"材料正文","knowledgeType":"CONCEPT",
                     "testMode":"CONCEPT","masteryScore":80,"masteryLevel":"MASTERED",
                     "reviewCount":2,"nextReviewAt":null}
                    """);
        });

        RecitationMaterialClient client = client("secret-token");
        var material = client.getMaterial(42L, 9L);

        assertThat(material.id()).isEqualTo(9L);
        assertThat(material.content()).isEqualTo("材料正文");
        assertThat(requestBody.toString()).isEqualTo("{\"userId\":42,\"materialId\":9}");
        assertThat(token.toString()).isEqualTo("secret-token");
    }

    @Test
    void mapsMissingOwnedMaterialToNotFound() throws Exception {
        startServer(exchange -> respond(exchange, 404, "{}"));

        assertThatThrownBy(() -> client("secret-token").getMaterial(42L, 9L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("知识材料不存在或不属于当前用户");
    }

    @Test
    void refusesToCallCoreWithoutInternalCredential() {
        ContentAutomationProperties properties = new ContentAutomationProperties();
        properties.setCoreBaseUrl("http://127.0.0.1:1");
        properties.setCoreInternalToken("");
        RecitationMaterialClient client = new RecitationMaterialClient(RestClient.builder(), properties);

        assertThatThrownBy(() -> client.getMaterial(42L, 9L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("尚未配置内网凭证");
    }

    private RecitationMaterialClient client(String token) {
        ContentAutomationProperties properties = new ContentAutomationProperties();
        properties.setCoreBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setCoreInternalToken(token);
        properties.setModelTimeoutMillis(2_000);
        return new RecitationMaterialClient(RestClient.builder(), properties);
    }

    private void startServer(Responder responder) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/agent/recitation/material/get", exchange -> {
            try {
                responder.respond(exchange);
            } catch (IOException ignored) {
                // The client may close the socket after a non-success response.
            }
        });
        server.start();
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @FunctionalInterface
    private interface Responder {
        void respond(HttpExchange exchange) throws IOException;
    }
}
