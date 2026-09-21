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
    private boolean credentialStoreEnabled;
    private String credentialStoreUrl = "";
    private String credentialStoreUsername = "";
    private String credentialStorePassword = "";
    private int credentialStoreQueryTimeoutSeconds = 2;

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

    public boolean isCredentialStoreEnabled() {
        return credentialStoreEnabled;
    }

    public void setCredentialStoreEnabled(boolean credentialStoreEnabled) {
        this.credentialStoreEnabled = credentialStoreEnabled;
    }

    public String getCredentialStoreUrl() {
        return credentialStoreUrl;
    }

    public void setCredentialStoreUrl(String credentialStoreUrl) {
        this.credentialStoreUrl = credentialStoreUrl;
    }

    public String getCredentialStoreUsername() {
        return credentialStoreUsername;
    }

    public void setCredentialStoreUsername(String credentialStoreUsername) {
        this.credentialStoreUsername = credentialStoreUsername;
    }

    public String getCredentialStorePassword() {
        return credentialStorePassword;
    }

    public void setCredentialStorePassword(String credentialStorePassword) {
        this.credentialStorePassword = credentialStorePassword;
    }

    public int getCredentialStoreQueryTimeoutSeconds() {
        return credentialStoreQueryTimeoutSeconds;
    }

    public void setCredentialStoreQueryTimeoutSeconds(int credentialStoreQueryTimeoutSeconds) {
        this.credentialStoreQueryTimeoutSeconds = credentialStoreQueryTimeoutSeconds;
    }
}
