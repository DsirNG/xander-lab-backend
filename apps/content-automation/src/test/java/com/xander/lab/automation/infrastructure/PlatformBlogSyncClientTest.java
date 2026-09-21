package com.xander.lab.automation.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xander.lab.automation.host.ContentAutomationProperties;
import com.xander.lab.integration.api.BlogSyncResult;
import com.xander.lab.publishing.api.BlogPostVO;
import com.xander.lab.publishing.application.BlogPrivateReadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlatformBlogSyncClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void forwardsExplicitOwnerAndPostIdsAndReadsSyncResult() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/platform-integration/blog-posts/42/sync/csdn", exchange -> {
            assertEquals("content-token", exchange.getRequestHeaders()
                    .getFirst("X-DinQor-Internal-Token"));
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            org.junit.jupiter.api.Assertions.assertTrue(request.contains("\"userId\":7"));
            org.junit.jupiter.api.Assertions.assertTrue(request.contains("\"postId\":42"));
            byte[] response = "{\"platform\":\"csdn\",\"externalId\":\"ext-42\",\"url\":\"https://csdn.example/42\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        ContentAutomationProperties properties = properties(16 * 1024);
        PlatformBlogSyncClient client = new PlatformBlogSyncClient(
                RestClient.builder(), properties, new ObjectMapper());

        BlogSyncResult result = client.syncCsdn(7L, 42L);

        assertEquals("csdn", result.platform());
        assertEquals("ext-42", result.externalId());
        assertEquals("https://csdn.example/42", result.url());
    }

    @Test
    void rejectsOversizedSyncResponse() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/platform-integration/blog-posts/42/sync/csdn", exchange -> {
            byte[] response = "123456789".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        PlatformBlogSyncClient client = new PlatformBlogSyncClient(
                RestClient.builder(), properties(8), new ObjectMapper());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> client.syncCsdn(7L, 42L));

        assertEquals(502, exception.getStatusCode().value());
    }

    @Test
    void carriesOwnedJuejinArticleProjectionToPlatform() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/platform-integration/blog-posts/42/sync/juejin", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            org.junit.jupiter.api.Assertions.assertTrue(request.contains("\"title\":\"标题\""));
            org.junit.jupiter.api.Assertions.assertTrue(request.contains("\"content\":\"正文\""));
            org.junit.jupiter.api.Assertions.assertTrue(request.contains("\"categoryName\":\"前端\""));
            byte[] response = "{\"platform\":\"juejin\",\"externalId\":\"article-1\",\"url\":\"https://juejin.cn/post/article-1\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        BlogPostVO post = new BlogPostVO();
        post.setId(42L);
        post.setTitle("标题");
        post.setContent("正文");
        post.setCategoryName("前端");
        post.setTags(List.of("Java"));
        BlogPrivateReadService readService = org.mockito.Mockito.mock(BlogPrivateReadService.class);
        org.mockito.Mockito.when(readService.findMine(7L, 42L)).thenReturn(post);
        PlatformBlogSyncClient client = new PlatformBlogSyncClient(
                RestClient.builder(), properties(16 * 1024), new ObjectMapper(),
                Optional.of(readService));

        BlogSyncResult result = client.syncJuejin(7L, 42L);

        assertEquals("article-1", result.externalId());
    }

    private ContentAutomationProperties properties(int maxResponseBytes) {
        ContentAutomationProperties properties = new ContentAutomationProperties();
        properties.setPlatformIntegrationBaseUrl("http://localhost:" + server.getAddress().getPort());
        properties.setPlatformIntegrationInternalToken("content-token");
        properties.setPlatformIntegrationConnectTimeoutMillis(2_000);
        properties.setPlatformIntegrationReadTimeoutMillis(2_000);
        properties.setPlatformIntegrationMaxResponseBytes(maxResponseBytes);
        return properties;
    }
}
