package com.xander.lab.automation.host;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Feature gate for the content-owned plan execution path during the drain. */
@ConfigurationProperties(prefix = "content-automation.plan-executor")
public class ContentPlanExecutionProperties {

    private boolean enabled;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
