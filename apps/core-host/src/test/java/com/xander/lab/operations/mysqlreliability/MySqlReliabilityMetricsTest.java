package com.xander.lab.operations.mysqlreliability;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlReliabilityMetricsTest {

    @Test
    void exposesRequiredReliabilityMetricNamesAsUnknownUntilEvidenceIsMounted() {
        MySqlReliabilityProperties properties = new MySqlReliabilityProperties();
        MySqlReliabilityState state = new MySqlReliabilityState(properties, new ObjectMapper());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new MySqlReliabilityMetrics(registry, state);

        assertThat(registry.get("backup_success").gauge().value()).isEqualTo(-1D);
        assertThat(registry.get("last_backup_age").gauge().value()).isEqualTo(-1D);
        assertThat(registry.get("binlog_lag").gauge().value()).isEqualTo(-1D);
        assertThat(registry.get("restore_drill_date").gauge().value()).isEqualTo(-1D);
        assertThat(registry.get("mysql_reliability_evidence_available").gauge().value()).isZero();
    }
}
