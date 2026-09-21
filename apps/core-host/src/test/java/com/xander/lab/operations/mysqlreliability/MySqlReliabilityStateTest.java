package com.xander.lab.operations.mysqlreliability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlReliabilityStateTest {

    @Test
    void readsBackupAndRestoreEvidenceWithoutInventingBinlogLag() throws Exception {
        Path evidence = Files.createTempFile("mysql-reliability-", ".json");
        try {
            Files.writeString(evidence, """
                    {
                      "backup_success": true,
                      "last_backup_age_seconds": 12,
                      "binlog_lag_seconds": null,
                      "restore_drill_date": "2026-09-21"
                    }
                    """);
            MySqlReliabilityProperties properties = new MySqlReliabilityProperties();
            properties.setEnabled(true);
            properties.setEvidenceFile(evidence.toString());
            MySqlReliabilityState state = new MySqlReliabilityState(properties, new ObjectMapper());

            assertThat(state.evidenceAvailable()).isEqualTo(1D);
            assertThat(state.backupSuccess()).isEqualTo(1D);
            assertThat(state.lastBackupAgeSeconds()).isEqualTo(12D);
            assertThat(state.binlogLagSeconds()).isEqualTo(-1D);
            assertThat(state.restoreDrillDate()).isPositive();
        } finally {
            Files.deleteIfExists(evidence);
        }
    }

    @Test
    void exposesUnknownValuesWhenEvidenceIsNotConfigured() {
        MySqlReliabilityProperties properties = new MySqlReliabilityProperties();
        MySqlReliabilityState state = new MySqlReliabilityState(properties, new ObjectMapper());

        assertThat(state.evidenceAvailable()).isZero();
        assertThat(state.backupSuccess()).isEqualTo(-1D);
        assertThat(state.lastBackupAgeSeconds()).isEqualTo(-1D);
        assertThat(state.binlogLagSeconds()).isEqualTo(-1D);
        assertThat(state.restoreDrillDate()).isEqualTo(-1D);
    }
}
