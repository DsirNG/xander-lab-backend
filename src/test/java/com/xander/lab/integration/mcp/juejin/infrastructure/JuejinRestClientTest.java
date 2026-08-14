package com.xander.lab.integration.mcp.juejin.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归测试：相对路径必须与 baseUrl 拼接后请求（曾用 URI.create 传相对路径，
 * 绕过 baseUrl 抛出 "URI is not absolute" 导致同步发布 400）。
 */
class JuejinRestClientTest {

    private HttpServer server;
    private JuejinRestClient client;
    private final List<String> receivedPaths = new ArrayList<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        JuejinPlatformProperties properties = new JuejinPlatformProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        client = new JuejinRestClient(properties);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        receivedPaths.add(exchange.getRequestURI().toString());
        receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String body = exchange.getRequestURI().getPath().startsWith("/tag_api/v1/query_tag_list")
                ? "{\"err_no\":0,\"data\":[{\"id\":\"6809640407484334093\",\"tag_name\":\"JavaScript\"}]}"
                : "{\"err_no\":0,\"data\":{\"id\":\"draft-1\"}}";
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response);
        }
    }

    @Test
    void createDraftResolvesRelativePathAgainstBaseUrl() {
        JuejinClient.Credential credential = new JuejinClient.Credential("cookie=abc", "csrf", "2608", "uuid-1");
        JsonNode result = client.createDraft(credential, "标题", "摘要", "正文", "cat-1", List.of("tag-1"), null);
        assertThat(result.path("data").path("id").asText()).isEqualTo("draft-1");
        assertThat(receivedPaths).singleElement().satisfies(path -> {
            assertThat(path).startsWith("/content_api/v1/article_draft/create?aid=2608&uuid=uuid-1");
        });
        assertThat(receivedBody.get()).contains("\"title\":\"标题\"");
    }

    @Test
    void publishDraftResolvesRelativePathAgainstBaseUrl() {
        JuejinClient.Credential credential = new JuejinClient.Credential("cookie=abc", "csrf", "2608", "uuid-1");
        client.publishDraft(credential, "draft-1");
        assertThat(receivedPaths).singleElement().satisfies(path -> {
            assertThat(path).startsWith("/content_api/v1/article/publish?aid=2608&uuid=uuid-1");
        });
        assertThat(receivedBody.get()).contains("\"draft_id\":\"draft-1\"");
    }

    @Test
    void searchTagsEncodesKeywordAndParsesTagList() {
        JsonNode result = client.searchTags("JavaScript", 5);
        assertThat(result.path("data").path(0).path("id").asText()).isEqualTo("6809640407484334093");
        assertThat(receivedPaths).singleElement().satisfies(path -> {
            assertThat(path).startsWith("/tag_api/v1/query_tag_list?key_word=");
            assertThat(path).contains("&cursor=0&limit=5");
        });
    }

    @Test
    void searchTagsEncodesChineseKeyword() {
        client.searchTags("后端", 3);
        assertThat(receivedPaths).singleElement().satisfies(path -> {
            assertThat(path).contains("key_word=%E5%90%8E%E7%AB%AF");
        });
    }
}
