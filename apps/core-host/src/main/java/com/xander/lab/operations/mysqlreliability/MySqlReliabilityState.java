package com.xander.lab.operations.mysqlreliability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** Reads deployment-owned backup evidence without treating missing evidence as success. */
@Component
public class MySqlReliabilityState {

    private static final Logger log = LoggerFactory.getLogger(MySqlReliabilityState.class);

    private final MySqlReliabilityProperties properties;
    private final ObjectMapper objectMapper;
    private volatile double backupSuccess = -1D;
    private volatile double lastBackupAgeSeconds = -1D;
    private volatile double binlogLagSeconds = -1D;
    private volatile double restoreDrillDate = -1D;
    private volatile double evidenceAvailable;

    public MySqlReliabilityState(MySqlReliabilityProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        refresh();
    }

    @Scheduled(fixedDelayString = "${mysql.reliability.refresh-interval-ms:30000}")
    public synchronized void refresh() {
        resetUnknown();
        if (!properties.isEnabled() || properties.getEvidenceFile() == null
                || properties.getEvidenceFile().isBlank()) {
            return;
        }
        Path evidencePath = Path.of(properties.getEvidenceFile());
        if (!Files.isRegularFile(evidencePath)) {
            log.warn("[MySQLReliability] evidence file is not available: {}", evidencePath);
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(evidencePath.toFile());
            backupSuccess = root.has("backup_success")
                    ? (root.path("backup_success").asBoolean() ? 1D : 0D) : -1D;
            lastBackupAgeSeconds = numberOrUnknown(root, "last_backup_age_seconds");
            binlogLagSeconds = numberOrUnknown(root, "binlog_lag_seconds");
            restoreDrillDate = restoreDateOrUnknown(root.path("restore_drill_date"));
            evidenceAvailable = 1D;
        } catch (IOException | RuntimeException ex) {
            log.warn("[MySQLReliability] cannot read evidence file {}: {}",
                    evidencePath, ex.getMessage());
        }
    }

    public double backupSuccess() {
        return backupSuccess;
    }

    public double lastBackupAgeSeconds() {
        return lastBackupAgeSeconds;
    }

    public double binlogLagSeconds() {
        return binlogLagSeconds;
    }

    public double restoreDrillDate() {
        return restoreDrillDate;
    }

    public double evidenceAvailable() {
        return evidenceAvailable;
    }

    private void resetUnknown() {
        backupSuccess = -1D;
        lastBackupAgeSeconds = -1D;
        binlogLagSeconds = -1D;
        restoreDrillDate = -1D;
        evidenceAvailable = 0D;
    }

    private double numberOrUnknown(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value == null || value.isNull() || !value.isNumber() ? -1D : value.asDouble();
    }

    private double restoreDateOrUnknown(JsonNode value) {
        if (value == null || value.isNull()) {
            return -1D;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        try {
            return LocalDate.parse(value.asText()).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        } catch (RuntimeException ignored) {
            return -1D;
        }
    }
}
