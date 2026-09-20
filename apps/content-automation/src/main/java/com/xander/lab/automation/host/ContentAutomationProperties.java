package com.xander.lab.automation.host;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime target used while the content-automation domain is being extracted. */
@ConfigurationProperties(prefix = "content-automation")
public class ContentAutomationProperties {

    private String coreBaseUrl = "http://localhost:30002";
    private boolean streamDrainEnabled;

    public String getCoreBaseUrl() {
        return coreBaseUrl;
    }

    public void setCoreBaseUrl(String coreBaseUrl) {
        this.coreBaseUrl = coreBaseUrl;
    }

    public boolean isStreamDrainEnabled() {
        return streamDrainEnabled;
    }

    public void setStreamDrainEnabled(boolean streamDrainEnabled) {
        this.streamDrainEnabled = streamDrainEnabled;
    }
}
