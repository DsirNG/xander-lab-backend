package com.xander.lab.core.stream;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Wave 2 switch for the Core-hosted legacy content streams. */
@Component
@ConfigurationProperties(prefix = "core.stream")
public class CoreHostStreamProperties {

    /** Rejects new legacy Agent/content SSE connections while old ones drain. */
    private boolean drainEnabled;

    public boolean isDrainEnabled() {
        return drainEnabled;
    }

    public void setDrainEnabled(boolean drainEnabled) {
        this.drainEnabled = drainEnabled;
    }
}
