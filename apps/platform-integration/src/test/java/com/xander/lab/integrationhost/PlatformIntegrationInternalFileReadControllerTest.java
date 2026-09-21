package com.xander.lab.integration.host;

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

class PlatformIntegrationInternalFileReadControllerTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void replacesPlatformTokenAndPreservesReadResponseHeaders() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/integration/files/export/csv", exchange -> {
            assertEquals("core-token", exchange.getRequestHeaders()
                    .getFirst("X-DinQor-Internal-Token"));
            assertThat(exchange.getRequestURI().getQuery()).isEqualTo("type=user");
            exchange.getResponseHeaders().add("Content-Type", "text/csv;charset=UTF-8");
            exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename*=UTF-8''user_export.csv");
            byte[] body = "id,name\n1,demo\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        PlatformIntegrationInternalFileReadController controller = controller();

        var response = controller.exportCsv("platform-token", "user");

        assertThat(response.getBody()).isNotEmpty();
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .contains("user_export.csv");
    }

    @Test
    void rejectsInvalidCallerTokenBeforeCallingCore() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller().download("wrong-token", "x.png"));

        assertEquals(404, exception.getStatusCode().value());
    }

    private PlatformIntegrationInternalFileReadController controller() {
        PlatformIntegrationProperties properties = new PlatformIntegrationProperties();
        properties.setCoreBaseUrl("http://localhost:" + server.getAddress().getPort());
        properties.setInternalToken("platform-token");
        properties.setCoreInternalToken("core-token");
        properties.setConnectTimeoutMillis(2_000);
        properties.setReadTimeoutMillis(2_000);
        properties.setMaxResponseBytes(16 * 1024);
        return new PlatformIntegrationInternalFileReadController(RestClient.builder(), properties);
    }
}
