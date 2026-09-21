package com.xander.lab.automation.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xander.lab.automation.host.ContentAutomationProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformCredentialStatusClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void forwardsAuthorizationAndReadsAuthorizedStatus() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/publishing/csdn/authorization/status", exchange -> {
            assertTrue("Bearer access-token".equals(
                    exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] response = "{\"data\":{\"status\":\"AUTHORIZED\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        PlatformCredentialStatusClient client = client(16 * 1024);

        assertTrue(client.isAuthorized("csdn", "Bearer access-token"));
    }

    @Test
    void treatsNonSuccessfulResponsesAsNotAuthorized() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/publishing/juejin/authorization/status", exchange -> {
            byte[] response = "upstream failure".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        assertFalse(client(16 * 1024).isAuthorized("juejin", "Bearer access-token"));
    }

    @Test
    void treatsOversizedResponsesAsNotAuthorized() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/publishing/csdn/authorization/status", exchange -> {
            byte[] response = "123456789".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        assertFalse(client(8).isAuthorized("csdn", "Bearer access-token"));
    }

    private PlatformCredentialStatusClient client(int maxResponseBytes) {
        ContentAutomationProperties properties = new ContentAutomationProperties();
        properties.setPlatformIntegrationBaseUrl("http://localhost:" + server.getAddress().getPort());
        properties.setPlatformIntegrationConnectTimeoutMillis(2_000);
        properties.setPlatformIntegrationReadTimeoutMillis(2_000);
        properties.setPlatformIntegrationMaxResponseBytes(maxResponseBytes);
        return new PlatformCredentialStatusClient(RestClient.builder(), properties, new ObjectMapper());
    }
}
