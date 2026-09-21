package com.xander.lab.integration.host;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Runtime settings for the temporary Core compatibility bridge. */
@Component
@ConfigurationProperties(prefix = "platform-integration")
public class PlatformIntegrationProperties {

    private String coreBaseUrl = "http://localhost:30002";
    private boolean internalApiEnabled;
    private String internalToken = "";
    private String coreInternalToken = "";
    private int connectTimeoutMillis = 2_000;
    private int readTimeoutMillis = 15_000;
    private int maxResponseBytes = 16 * 1024 * 1024;

    public String getCoreBaseUrl() {
        return coreBaseUrl;
    }

    public void setCoreBaseUrl(String coreBaseUrl) {
        this.coreBaseUrl = coreBaseUrl;
    }

    public boolean isInternalApiEnabled() {
        return internalApiEnabled;
    }

    public void setInternalApiEnabled(boolean internalApiEnabled) {
        this.internalApiEnabled = internalApiEnabled;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    public String getCoreInternalToken() {
        return coreInternalToken;
    }

    public void setCoreInternalToken(String coreInternalToken) {
        this.coreInternalToken = coreInternalToken;
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public void setConnectTimeoutMillis(int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    public int getReadTimeoutMillis() {
        return readTimeoutMillis;
    }

    public void setReadTimeoutMillis(int readTimeoutMillis) {
        this.readTimeoutMillis = readTimeoutMillis;
    }

    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(int maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }
}
