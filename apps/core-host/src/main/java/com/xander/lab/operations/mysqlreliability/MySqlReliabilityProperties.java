package com.xander.lab.operations.mysqlreliability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** External backup/restore evidence consumed by the V9 reliability metrics. */
@Component
@ConfigurationProperties(prefix = "mysql.reliability")
public class MySqlReliabilityProperties {

    private boolean enabled;
    private String evidenceFile = "";
    private long refreshIntervalMs = 30_000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEvidenceFile() {
        return evidenceFile;
    }

    public void setEvidenceFile(String evidenceFile) {
        this.evidenceFile = evidenceFile;
    }

    public long getRefreshIntervalMs() {
        return refreshIntervalMs;
    }

    public void setRefreshIntervalMs(long refreshIntervalMs) {
        this.refreshIntervalMs = refreshIntervalMs;
    }
}
