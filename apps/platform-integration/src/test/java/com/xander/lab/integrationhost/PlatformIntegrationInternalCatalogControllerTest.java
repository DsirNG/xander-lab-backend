package com.xander.lab.integration.host;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlatformIntegrationInternalCatalogControllerTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void replacesPlatformTokenAndForwardsExplicitOwner() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/integration/platform-credentials/7/juejin/catalog/sync",
                exchange -> {
                    assertEquals("core-token", exchange.getRequestHeaders()
                            .getFirst("X-DinQor-Internal-Token"));
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                });
        server.start();

        PlatformIntegrationInternalCatalogController controller = controller();

        controller.sync("platform-token", 7L);

        assertThat(server.getAddress().getPort()).isPositive();
    }

    @Test
    void rejectsInvalidCallerTokenBeforeCallingCore() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller().sync("wrong-token", 7L));

        assertEquals(404, exception.getStatusCode().value());
    }

    private PlatformIntegrationInternalCatalogController controller() {
        PlatformIntegrationProperties properties = new PlatformIntegrationProperties();
        properties.setCoreBaseUrl("http://localhost:" + server.getAddress().getPort());
        properties.setInternalToken("platform-token");
        properties.setCoreInternalToken("core-token");
        properties.setConnectTimeoutMillis(2_000);
        properties.setReadTimeoutMillis(2_000);
        return new PlatformIntegrationInternalCatalogController(RestClient.builder(), properties);
    }
}
