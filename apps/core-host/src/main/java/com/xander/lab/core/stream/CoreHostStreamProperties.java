package com.xander.lab.core.stream;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Wave 2 switch for the Core-hosted legacy content streams. */
@Component
@ConfigurationProperties(prefix = "core.stream")
public class CoreHostStreamProperties {

    /** Rejects new legacy Agent/content SSE connections while old ones drain. */
    private boolean drainEnabled;

    /** Stable per-process label used to evaluate drain state instance by instance. */
    private String instanceId = "unknown";

    /** Unix epoch milliseconds at which Phase 3 force-drain starts; zero keeps it disabled. */
    private long drainDeadlineAt;

    public boolean isDrainEnabled() {
        return drainEnabled;
    }

    public void setDrainEnabled(boolean drainEnabled) {
        this.drainEnabled = drainEnabled;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public long getDrainDeadlineAt() {
        return drainDeadlineAt;
    }

    public void setDrainDeadlineAt(long drainDeadlineAt) {
        this.drainDeadlineAt = drainDeadlineAt;
    }
}
