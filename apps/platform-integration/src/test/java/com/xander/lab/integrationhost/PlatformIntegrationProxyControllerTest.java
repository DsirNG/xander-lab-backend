package com.xander.lab.integration.host;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlatformIntegrationProxyControllerTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void forwardsPathQueryBodyAndResponseHeaders() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/publishing/csdn/authorization/status", exchange -> {
            assertEquals("a=1", exchange.getRequestURI().getRawQuery());
            assertEquals("Bearer test", exchange.getRequestHeaders().getFirst("Authorization"));
            assertArrayEquals("payload".getBytes(StandardCharsets.UTF_8), exchange.getRequestBody().readAllBytes());
            byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-Integration-Test", "passed");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        PlatformIntegrationProperties properties = properties(16 * 1024);
        PlatformIntegrationProxyController controller = controller(properties);
        MockHttpServletRequest request = request("POST", "/api/publishing/csdn/authorization/status", "a=1");
        request.addHeader("Authorization", "Bearer test");
        request.setContent("payload".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<byte[]> response = controller.proxy(request, new MockHttpServletResponse());

        assertEquals(200, response.getStatusCode().value());
        assertEquals("passed", response.getHeaders().getFirst("X-Integration-Test"));
        assertArrayEquals("{\"ok\":true}".getBytes(StandardCharsets.UTF_8), response.getBody());
    }

    @Test
    void rejectsOversizedCoreResponseWithBadGateway() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/publishing/csdn/authorization/status", exchange -> {
            byte[] response = "123456789".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        PlatformIntegrationProperties properties = properties(8);
        PlatformIntegrationProxyController controller = controller(properties);
        MockHttpServletRequest request = request("GET", "/api/publishing/csdn/authorization/status", null);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.proxy(request, new MockHttpServletResponse()));

        assertEquals(502, exception.getStatusCode().value());
    }

    private PlatformIntegrationProxyController controller(PlatformIntegrationProperties properties) {
        return new PlatformIntegrationProxyController(RestClient.builder(), properties);
    }

    private PlatformIntegrationProperties properties(int maxResponseBytes) {
        PlatformIntegrationProperties properties = new PlatformIntegrationProperties();
        properties.setCoreBaseUrl("http://localhost:" + server.getAddress().getPort());
        properties.setConnectTimeoutMillis(2_000);
        properties.setReadTimeoutMillis(2_000);
        properties.setMaxResponseBytes(maxResponseBytes);
        return properties;
    }

    private MockHttpServletRequest request(String method, String uri, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(method);
        request.setRequestURI(uri);
        request.setQueryString(query);
        return request;
    }
}
