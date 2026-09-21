package com.xander.lab.integration.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlatformIntegrationInternalAuthorizationControllerTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void replacesPlatformTokenWithCoreTokenForStartAndCommand() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/integration/platform-credentials/7/csdn/authorization/start",
                exchange -> {
                    assertEquals("core-token", exchange.getRequestHeaders()
                            .getFirst("X-DinQor-Internal-Token"));
                    byte[] response = "{\"status\":\"PENDING\",\"qrCodeDataUrl\":\"qr\","
                            .concat("\"expiresInSeconds\":30,\"queuePosition\":null,"
                                    + "\"estimatedWaitSeconds\":null}")
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.createContext("/internal/integration/platform-credentials/7/csdn/authorization/cancel",
                exchange -> {
                    assertEquals("core-token", exchange.getRequestHeaders()
                            .getFirst("X-DinQor-Internal-Token"));
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                });
        server.start();

        PlatformIntegrationInternalAuthorizationController controller = controller();

        var start = controller.start("platform-token", 7L, "csdn");
        controller.cancel("platform-token", 7L, "csdn");

        assertThat(start.status()).isEqualTo("PENDING");
        assertThat(start.qrCodeDataUrl()).isEqualTo("qr");
    }

    @Test
    void rejectsInvalidCallerTokenBeforeCallingCore() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller().start("wrong-token", 7L, "csdn"));

        assertEquals(404, exception.getStatusCode().value());
    }

    private PlatformIntegrationInternalAuthorizationController controller() {
        PlatformIntegrationProperties properties = new PlatformIntegrationProperties();
        properties.setCoreBaseUrl("http://localhost:" + server.getAddress().getPort());
        properties.setInternalToken("platform-token");
        properties.setCoreInternalToken("core-token");
        properties.setConnectTimeoutMillis(2_000);
        properties.setReadTimeoutMillis(2_000);
        properties.setMaxResponseBytes(16 * 1024);
        return new PlatformIntegrationInternalAuthorizationController(
                RestClient.builder(), properties, new ObjectMapper());
    }
}
