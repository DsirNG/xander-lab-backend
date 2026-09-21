package com.xander.lab.automation.host;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime controls for the content-owned durable plan poller. */
@ConfigurationProperties(prefix = "content-automation.plan-scheduler")
public class ContentPlanSchedulerProperties {

    private boolean enabled;
    private int batchSize = 5;
    private long claimTimeoutMs = 1_800_000L;
    private long pollIntervalMs = 15_000L;
    private long initialDelayMs = 15_000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public long getClaimTimeoutMs() {
        return claimTimeoutMs;
    }

    public void setClaimTimeoutMs(long claimTimeoutMs) {
        this.claimTimeoutMs = claimTimeoutMs;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
    }
}
