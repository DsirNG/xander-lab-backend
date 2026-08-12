package com.xander.lab.blogagent.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Adapter tests for classified transient retry against a local mock gateway. */
class BlogAgentModelClientTest {

    private static final String UPSTREAM_ERROR =
            "{\"error\":{\"message\":\"Upstream request failed\",\"type\":\"upstream_error\"}}";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private BlogAgentModelClient client;
    private final AtomicInteger requests = new AtomicInteger();

    @FunctionalInterface
    private interface Responder {
        void respond(HttpExchange exchange) throws IOException;
    }

    private void startServer(List<Responder> responders) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            int index = Math.min(requests.getAndIncrement(), responders.size() - 1);
            exchange.getRequestBody().readAllBytes();
            try {
                responders.get(index).respond(exchange);
            } catch (IOException ignored) {
                // client may close the socket early after an error status
            }
        });
        server.start();
        BlogAgentProperties properties = new BlogAgentProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey("test-key");
        properties.setModel("test-model");
        properties.setWebSearchEnabled(false);
        client = new BlogAgentModelClient(properties, objectMapper);
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private Responder respondJson(int status, String body) {
        return exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        };
    }

    private Responder respondStream(String body) {
        return exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        };
    }

    private Responder respondTruncatedStream(String body, int advertisedExtraBytes) {
        return exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length + advertisedExtraBytes);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        };
    }

    private String streamBody() throws IOException {
        String delta = objectMapper.writeValueAsString(
                Map.of("type", "response.output_text.delta", "delta", "{\"angle\":\"深度优先\"}"));
        String completed = objectMapper.writeValueAsString(Map.of("type", "response.completed"));
        return "data: " + delta + "\n\ndata: " + completed + "\n\n";
    }

    private String streamErrorEvent() throws IOException {
        String event = objectMapper.writeValueAsString(
                Map.of("type", "error", "error", Map.of("message", "Upstream request failed", "type", "upstream_error")));
        return "data: " + event + "\n\n";
    }

    private String streamDeltaThenError() throws IOException {
        String delta = objectMapper.writeValueAsString(
                Map.of("type", "response.output_text.delta", "delta", "{\"angle\":"));
        return "data: " + delta + "\n\n" + streamErrorEvent();
    }

    @Test
    void retriesTransientGatewayFailureAndSucceeds() throws Exception {
        startServer(List.of(
                respondJson(502, UPSTREAM_ERROR),
                respondJson(502, UPSTREAM_ERROR),
                respondJson(200, "{\"output_text\":\"{\\\"angle\\\":\\\"深度优先\\\"}\"}")));
        JsonNode result = client.analyze("测试主题");
        assertEquals("深度优先", result.path("angle").asText());
        assertEquals(3, requests.get());
    }

    @Test
    void failsWithFriendlyMessageAfterRetryExhaustion() throws Exception {
        startServer(List.of(
                respondJson(502, UPSTREAM_ERROR),
                respondJson(502, UPSTREAM_ERROR),
                respondJson(502, UPSTREAM_ERROR)));
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> client.analyze("测试主题"));
        assertTrue(thrown.getMessage().contains("模型服务暂时不可用（HTTP 502）"));
        assertTrue(thrown.getMessage().contains("已自动重试 2 次"));
        assertEquals(3, requests.get());
    }

    @Test
    void doesNotRetryValidationFailure() throws Exception {
        startServer(List.of(
                respondJson(400, "{\"error\":{\"message\":\"bad request\",\"type\":\"invalid_request_error\"}}")));
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> client.analyze("测试主题"));
        assertTrue(thrown.getMessage().contains("HTTP 400"));
        assertEquals(1, requests.get());
    }

    @Test
    void retriesStreamGatewayFailureBeforeAnyDelta() throws Exception {
        startServer(List.of(respondJson(502, UPSTREAM_ERROR), respondStream(streamBody())));
        StringBuilder deltas = new StringBuilder();
        JsonNode result = client.createArticleStream("测试主题", deltas::append);
        assertEquals("{\"angle\":\"深度优先\"}", result.toString());
        assertEquals("{\"angle\":\"深度优先\"}", deltas.toString());
        assertEquals(2, requests.get());
    }

    @Test
    void retriesStreamSseUpstreamErrorEvent() throws Exception {
        startServer(List.of(respondStream(streamErrorEvent()), respondStream(streamBody())));
        StringBuilder deltas = new StringBuilder();
        JsonNode result = client.createArticleStream("测试主题", deltas::append);
        assertEquals("{\"angle\":\"深度优先\"}", result.toString());
        assertEquals(2, requests.get());
    }

    @Test
    void doesNotRetrySseErrorAfterVisibleDelta() throws Exception {
        startServer(List.of(respondStream(streamDeltaThenError()), respondStream(streamBody())));
        StringBuilder deltas = new StringBuilder();

        assertThrows(RuntimeException.class,
                () -> client.createArticleStream("测试主题", deltas::append));

        assertEquals("{\"angle\":", deltas.toString());
        assertEquals(1, requests.get());
    }

    @Test
    void doesNotRetryTruncatedConnectionAfterVisibleDelta() throws Exception {
        String stream = streamDeltaThenError();
        String deltaOnly = stream.substring(0, stream.indexOf("data: ", 1));
        startServer(List.of(respondTruncatedStream(deltaOnly, 64), respondStream(streamBody())));
        StringBuilder deltas = new StringBuilder();

        assertThrows(RuntimeException.class,
                () -> client.createArticleStream("测试主题", deltas::append));

        assertEquals("{\"angle\":", deltas.toString());
        assertEquals(1, requests.get());
    }

    @Test
    void doesNotRetryTransientConsumerFailureAfterVisibleDelta() throws Exception {
        startServer(List.of(respondStream(streamBody()), respondStream(streamBody())));
        AtomicInteger delivered = new AtomicInteger();

        assertThrows(BlogAgentModelClient.ModelGatewayException.class,
                () -> client.createArticleStream("测试主题", delta -> {
                    delivered.incrementAndGet();
                    throw new BlogAgentModelClient.ModelGatewayException(502, "consumer failed");
                }));

        assertEquals(1, delivered.get());
        assertEquals(1, requests.get());
    }
}
