package com.xander.lab.integration.host;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Platform-owned public boundary for file downloads and export reads. */
@RestController
@RequestMapping("/api")
@ConditionalOnProperty(prefix = "platform-integration", name = "internal-api-enabled",
        havingValue = "true")
public class PlatformFileReadController {

    private final PlatformIntegrationInternalFileReadController fileReadBridge;
    private final PlatformIntegrationProperties properties;

    public PlatformFileReadController(
            PlatformIntegrationInternalFileReadController fileReadBridge,
            PlatformIntegrationProperties properties) {
        this.fileReadBridge = fileReadBridge;
        this.properties = properties;
    }

    @GetMapping("/download/{filename:.+}")
    public ResponseEntity<byte[]> download(@PathVariable String filename) {
        return fileReadBridge.download(properties.getInternalToken(), filename);
    }

    @GetMapping("/export/csv")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(defaultValue = "blog") String type) {
        return fileReadBridge.exportCsv(properties.getInternalToken(), type);
    }

    @GetMapping("/export/excel")
    public ResponseEntity<byte[]> exportExcel(
            @RequestParam(defaultValue = "blog") String type) {
        return fileReadBridge.exportExcel(properties.getInternalToken(), type);
    }
}
