package com.xander.lab.automation.host;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime target used while the content-automation domain is being extracted. */
@ConfigurationProperties(prefix = "content-automation")
public class ContentAutomationProperties {

    private String coreBaseUrl = "http://localhost:30002";
    private String coreInternalToken = "";
    private int coreConnectTimeoutMillis = 2_000;
    private int coreReadTimeoutMillis = 15_000;
    private int coreMaxResponseBytes = 1 * 1024 * 1024;
    private boolean publishingCreateEnabled;
    private String platformIntegrationBaseUrl = "http://localhost:30005";
    private String platformIntegrationInternalToken = "";
    private int platformIntegrationConnectTimeoutMillis = 2_000;
    private int platformIntegrationReadTimeoutMillis = 30_000;
    private int platformIntegrationMaxResponseBytes = 1 * 1024 * 1024;
    private int modelTimeoutMillis = 30_000;
    private boolean streamDrainEnabled;
    private String streamInstanceId = "unknown";
    private long streamDrainDeadlineAt;

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

    public int getCoreConnectTimeoutMillis() {
        return coreConnectTimeoutMillis;
    }

    public void setCoreConnectTimeoutMillis(int coreConnectTimeoutMillis) {
        this.coreConnectTimeoutMillis = coreConnectTimeoutMillis;
    }

    public int getCoreReadTimeoutMillis() {
        return coreReadTimeoutMillis;
    }

    public void setCoreReadTimeoutMillis(int coreReadTimeoutMillis) {
        this.coreReadTimeoutMillis = coreReadTimeoutMillis;
    }

    public int getCoreMaxResponseBytes() {
        return coreMaxResponseBytes;
    }

    public void setCoreMaxResponseBytes(int coreMaxResponseBytes) {
        this.coreMaxResponseBytes = coreMaxResponseBytes;
    }

    public boolean isPublishingCreateEnabled() {
        return publishingCreateEnabled;
    }

    public void setPublishingCreateEnabled(boolean publishingCreateEnabled) {
        this.publishingCreateEnabled = publishingCreateEnabled;
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

    public String getStreamInstanceId() {
        return streamInstanceId;
    }

    public void setStreamInstanceId(String streamInstanceId) {
        this.streamInstanceId = streamInstanceId;
    }

    public long getStreamDrainDeadlineAt() {
        return streamDrainDeadlineAt;
    }

    public void setStreamDrainDeadlineAt(long streamDrainDeadlineAt) {
        this.streamDrainDeadlineAt = streamDrainDeadlineAt;
    }

}
