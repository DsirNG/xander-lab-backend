package com.xander.lab.blogagent.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xander.lab.admin.application.ModelGateway;
import com.xander.lab.admin.application.ModelGateway.ModelEndpoint;
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
        // 生产默认读超时 300s，截断流用例需要等满超时才暴露连接断开；
        // 测试内缩短超时，避免单个用例空等 5 分钟。
        properties.setConnectTimeoutMillis(2_000);
        properties.setReadTimeoutMillis(2_000);
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
        JsonNode result = client.analyze("测试主题").payload();
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
        JsonNode result = client.createArticleStream("测试主题", deltas::append).payload();
        assertEquals("{\"angle\":\"深度优先\"}", result.toString());
        assertEquals("{\"angle\":\"深度优先\"}", deltas.toString());
        assertEquals(2, requests.get());
    }

    @Test
    void retriesStreamSseUpstreamErrorEvent() throws Exception {
        startServer(List.of(respondStream(streamErrorEvent()), respondStream(streamBody())));
        StringBuilder deltas = new StringBuilder();
        JsonNode result = client.createArticleStream("测试主题", deltas::append).payload();
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

    /**
     * 配成 CHAT_COMPLETIONS 时，流式请求要打到 /chat/completions 并解析该协议的事件。
     *
     * <p>此前该字段不被读取，请求永远发往 /responses：运维把它改成聊天协议后，
     * 实际协议没有任何变化，只在日志里留下一条看起来"已生效"的配置。</p>
     */
    @Test
    void streamsThroughChatCompletionsWhenTheEndpointSaysSo() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        java.util.List<String> paths = new java.util.ArrayList<>();
        StringBuilder body = new StringBuilder();
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            body.append(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            // Chat Completions 的帧没有 type：增量在 choices[0].delta.content。
            byte[] bytes = ("data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"angle\\\":\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"\\\"深度优先\\\"}\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();

        BlogAgentProperties properties = new BlogAgentProperties();
        properties.setWebSearchEnabled(false);
        ModelEndpoint endpoint = new ModelEndpoint(6L, "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-key", "grok-4.5", ModelGateway.API_STYLE_CHAT_COMPLETIONS);
        BlogAgentModelClient chatClient = new BlogAgentModelClient(properties, objectMapper,
                featureKey -> List.of(endpoint));

        StringBuilder deltas = new StringBuilder();
        JsonNode result = chatClient.createArticleStream("测试主题", deltas::append).payload();

        assertEquals("{\"angle\":\"深度优先\"}", result.toString());
        assertEquals("{\"angle\":\"深度优先\"}", deltas.toString());
        assertEquals(List.of("/chat/completions"), paths);
        // body 也必须是聊天协议形态：messages 里有 system 消息，不再有 instructions/input。
        assertTrue(body.toString().contains("\"messages\""));
        assertTrue(!body.toString().contains("\"instructions\""));
    }
}
