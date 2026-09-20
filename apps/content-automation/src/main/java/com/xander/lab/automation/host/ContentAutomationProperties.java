package com.xander.lab.automation.host;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime target used while the content-automation domain is being extracted. */
@ConfigurationProperties(prefix = "content-automation")
public class ContentAutomationProperties {

    private String coreBaseUrl = "http://localhost:30002";
    private String coreInternalToken = "";
    private String platformIntegrationBaseUrl = "http://localhost:30005";
    private int modelTimeoutMillis = 30_000;
    private boolean streamDrainEnabled;

    public String getCoreBaseUrl() {
        return coreBaseUrl;
    }

    public void setCoreBaseUrl(String coreBaseUrl) {
        this.coreBaseUrl = coreBaseUrl;
    }

    public String getCoreInternalToken() {
        return coreInternalToken;
    }

    public void setCoreInternalToken(String coreInternalToken) {
        this.coreInternalToken = coreInternalToken;
    }

    public String getPlatformIntegrationBaseUrl() {
        return platformIntegrationBaseUrl;
    }

    public void setPlatformIntegrationBaseUrl(String platformIntegrationBaseUrl) {
        this.platformIntegrationBaseUrl = platformIntegrationBaseUrl;
    }

    public int getModelTimeoutMillis() {
        return modelTimeoutMillis;
    }

    public void setModelTimeoutMillis(int modelTimeoutMillis) {
        this.modelTimeoutMillis = modelTimeoutMillis;
    }

    public boolean isStreamDrainEnabled() {
        return streamDrainEnabled;
    }

    public void setStreamDrainEnabled(boolean streamDrainEnabled) {
        this.streamDrainEnabled = streamDrainEnabled;
    }
}
