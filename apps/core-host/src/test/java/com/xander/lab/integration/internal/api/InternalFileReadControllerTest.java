package com.xander.lab.integration.internal.api;

import com.xander.lab.integration.file.FileService;
import com.xander.lab.integration.internal.application.IntegrationInternalApiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalFileReadControllerTest {

    @Test
    void exportsThroughTheTokenProtectedCoreBoundary() {
        FileService fileService = mock(FileService.class);
        when(fileService.generateCsvContent("user")).thenReturn("id,name\n1,demo\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        InternalFileReadController controller = controller(fileService);

        ResponseEntity<byte[]> response = controller.exportCsv("core-token", "user");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isEqualTo(
                MediaType.parseMediaType("text/csv;charset=UTF-8"));
        assertThat(response.getBody()).isNotEmpty();
        verify(fileService).generateCsvContent("user");
    }

    @Test
    void rejectsWrongInternalTokenBeforeReadingFile() {
        FileService fileService = mock(FileService.class);
        InternalFileReadController controller = controller(fileService);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.exportExcel("wrong-token", "blog"));

        assertThat(exception.getStatusCode().value()).isEqualTo(404);
    }

    private InternalFileReadController controller(FileService fileService) {
        IntegrationInternalApiProperties properties = new IntegrationInternalApiProperties();
        properties.setInternalToken("core-token");
        return new InternalFileReadController(fileService, properties);
    }
}
