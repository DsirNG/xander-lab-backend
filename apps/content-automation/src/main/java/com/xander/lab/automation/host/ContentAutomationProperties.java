package com.xander.lab.automation.host;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime target used while the content-automation domain is being extracted. */
@ConfigurationProperties(prefix = "content-automation")
public class ContentAutomationProperties {

    private String coreBaseUrl = "http://localhost:30002";
    private String coreInternalToken = "";
    private String platformIntegrationBaseUrl = "http://localhost:30005";
    private String platformIntegrationInternalToken = "";
    private int platformIntegrationConnectTimeoutMillis = 2_000;
    private int platformIntegrationReadTimeoutMillis = 30_000;
    private int platformIntegrationMaxResponseBytes = 1 * 1024 * 1024;
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

    public String getPlatformIntegrationInternalToken() {
        return platformIntegrationInternalToken;
    }

    public void setPlatformIntegrationInternalToken(String platformIntegrationInternalToken) {
        this.platformIntegrationInternalToken = platformIntegrationInternalToken;
    }

    public int getPlatformIntegrationConnectTimeoutMillis() {
        return platformIntegrationConnectTimeoutMillis;
    }

    public void setPlatformIntegrationConnectTimeoutMillis(int platformIntegrationConnectTimeoutMillis) {
        this.platformIntegrationConnectTimeoutMillis = platformIntegrationConnectTimeoutMillis;
    }

    public int getPlatformIntegrationReadTimeoutMillis() {
        return platformIntegrationReadTimeoutMillis;
    }

    public void setPlatformIntegrationReadTimeoutMillis(int platformIntegrationReadTimeoutMillis) {
        this.platformIntegrationReadTimeoutMillis = platformIntegrationReadTimeoutMillis;
    }

    public int getPlatformIntegrationMaxResponseBytes() {
        return platformIntegrationMaxResponseBytes;
    }

    public void setPlatformIntegrationMaxResponseBytes(int platformIntegrationMaxResponseBytes) {
        this.platformIntegrationMaxResponseBytes = platformIntegrationMaxResponseBytes;
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
