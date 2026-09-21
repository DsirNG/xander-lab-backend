package com.xander.lab.integration.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xander.lab.integration.api.BlogSyncResult;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformIntegrationInternalSyncControllerTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void replacesContentTokenWithCoreTokenAndForwardsExplicitRequest() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/integration/blog-posts/42/sync/csdn", exchange -> {
            assertEquals("core-token", exchange.getRequestHeaders()
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

        PlatformIntegrationInternalSyncController controller = controller();

        BlogSyncResult result = controller.syncCsdn("content-token", 42L,
                new PlatformIntegrationInternalSyncController.CsdnSyncRequest(7L, 42L));

        assertEquals("ext-42", result.externalId());
    }

    @Test
    void rejectsInvalidCallerTokenBeforeCallingCore() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();

        PlatformIntegrationInternalSyncController controller = controller();

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.syncCsdn("wrong-token", 42L,
                        new PlatformIntegrationInternalSyncController.CsdnSyncRequest(7L, 42L)));

        assertEquals(404, exception.getStatusCode().value());
    }

    @Test
    void publishesLocallyWhenContentPayloadAndPlatformOwnerAreAvailable() {
        PlatformJuejinPublisher publisher = mock(PlatformJuejinPublisher.class);
        BlogSyncResult expected = new BlogSyncResult(
                "juejin", "article-42", "https://juejin.cn/post/article-42");
        when(publisher.publish(any(PlatformJuejinPublisher.PublishRequest.class))).thenReturn(expected);
        PlatformIntegrationProperties properties = new PlatformIntegrationProperties();
        properties.setInternalToken("content-token");
        properties.setCoreInternalToken("core-token");
        properties.setCoreBaseUrl("http://127.0.0.1:1");
        PlatformIntegrationInternalSyncController controller = new PlatformIntegrationInternalSyncController(
                RestClient.builder(), properties, new ObjectMapper(), Optional.of(publisher));

        BlogSyncResult actual = controller.syncJuejin("content-token", 42L,
                new PlatformIntegrationInternalSyncController.JuejinSyncRequest(
                        7L, 42L, null, null,
                        new PlatformIntegrationInternalSyncController.BlogPostPayload(
                                42L, "标题", "摘要", "正文", "frontend", "前端", "", List.of("Java"))));

        assertEquals(expected, actual);
        verify(publisher).publish(any(PlatformJuejinPublisher.PublishRequest.class));
    }

    private PlatformIntegrationInternalSyncController controller() {
        PlatformIntegrationProperties properties = new PlatformIntegrationProperties();
        properties.setCoreBaseUrl("http://localhost:" + server.getAddress().getPort());
        properties.setInternalToken("content-token");
        properties.setCoreInternalToken("core-token");
        properties.setConnectTimeoutMillis(2_000);
        properties.setReadTimeoutMillis(2_000);
        properties.setMaxResponseBytes(16 * 1024);
        return new PlatformIntegrationInternalSyncController(
                RestClient.builder(), properties, new ObjectMapper());
    }
}
