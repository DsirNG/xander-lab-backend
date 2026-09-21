package com.xander.lab.publishing.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xander.lab.automation.host.ContentAutomationProperties;
import com.xander.lab.publishing.api.BlogPostDTO;
import com.xander.lab.publishing.api.BlogPostVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContentBlogCreateClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void forwardsExplicitOwnerAndIdempotencyKeyToCore() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/content/blog-posts", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-DinQor-Internal-Token"))
                    .isEqualTo("content-token");
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).contains("\"userId\":7", "\"requestId\":\"request-1\"");
            byte[] response = "{\"id\":42,\"title\":\"title\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        ContentBlogCreateClient client = new ContentBlogCreateClient(
                RestClient.builder(), properties(), new ObjectMapper());
        BlogPostDTO request = new BlogPostDTO();
        request.setTitle("title");
        request.setContent("content");

        BlogPostVO result = client.create(7L, request, true, "request-1");

        assertThat(result.getId()).isEqualTo(42L);
        assertThat(result.getTitle()).isEqualTo("title");
    }

    @Test
    void rejectsOversizedCoreResponse() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/content/blog-posts", exchange -> {
            byte[] response = "123456789".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        ContentAutomationProperties properties = properties();
        properties.setCoreMaxResponseBytes(8);
        ContentBlogCreateClient client = new ContentBlogCreateClient(
                RestClient.builder(), properties, new ObjectMapper());
        BlogPostDTO request = new BlogPostDTO();
        request.setTitle("title");
        request.setContent("content");

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> client.create(7L, request, true, null));

        assertThat(exception.getStatusCode().value()).isEqualTo(502);
    }

    private ContentAutomationProperties properties() {
        ContentAutomationProperties properties = new ContentAutomationProperties();
        properties.setCoreBaseUrl("http://localhost:" + server.getAddress().getPort());
        properties.setCoreInternalToken("content-token");
        properties.setCoreConnectTimeoutMillis(2_000);
        properties.setCoreReadTimeoutMillis(2_000);
        properties.setCoreMaxResponseBytes(16 * 1024);
        return properties;
    }
}
