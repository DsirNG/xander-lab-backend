package com.xander.lab.integration.host;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformFileReadControllerTest {

    @Test
    void ownsDownloadAndExportReadsWithoutRequiringAUserIdentity() {
        PlatformIntegrationInternalFileReadController bridge =
                mock(PlatformIntegrationInternalFileReadController.class);
        ResponseEntity<byte[]> download = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"x.png\"")
                .body(new byte[]{1, 2});
        when(bridge.download("platform-token", "x.png")).thenReturn(download);
        when(bridge.exportCsv("platform-token", "user")).thenReturn(ResponseEntity.ok(new byte[]{3}));

        PlatformFileReadController controller = new PlatformFileReadController(bridge, properties());

        assertThat(controller.download("x.png").getBody()).containsExactly(1, 2);
        assertThat(controller.exportCsv("user").getBody()).containsExactly(3);
        verify(bridge).download("platform-token", "x.png");
        verify(bridge).exportCsv("platform-token", "user");
    }

    private PlatformIntegrationProperties properties() {
        PlatformIntegrationProperties properties = new PlatformIntegrationProperties();
        properties.setInternalToken("platform-token");
        return properties;
    }
}
