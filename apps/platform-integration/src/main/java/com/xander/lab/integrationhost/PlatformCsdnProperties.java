package com.xander.lab.integration.host;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** CSDN authorization settings owned by the Platform deployment unit. */
@Component
@ConfigurationProperties(prefix = "platform-integration.csdn")
public class PlatformCsdnProperties {

    private String baseUrl = "https://bizapi.csdn.net";
    private String apiKey = "";
    private String webConsoleSecret = "";
    private String seleniumUrl = "http://selenium:4444/wd/hub";
    private String credentialEncryptionKey = "";
    private int authorizationTimeoutSeconds = 35;
    private long cleanupIntervalMs = 15_000;
    private int connectTimeoutMillis = 10_000;
    private int readTimeoutMillis = 30_000;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getWebConsoleSecret() {
        return webConsoleSecret;
    }

    public void setWebConsoleSecret(String webConsoleSecret) {
        this.webConsoleSecret = webConsoleSecret;
    }

    public String getSeleniumUrl() {
        return seleniumUrl;
    }

    public void setSeleniumUrl(String seleniumUrl) {
        this.seleniumUrl = seleniumUrl;
    }

    public String getCredentialEncryptionKey() {
        return credentialEncryptionKey;
    }

    public void setCredentialEncryptionKey(String credentialEncryptionKey) {
        this.credentialEncryptionKey = credentialEncryptionKey;
    }

    public int getAuthorizationTimeoutSeconds() {
        return authorizationTimeoutSeconds;
    }

    public void setAuthorizationTimeoutSeconds(int authorizationTimeoutSeconds) {
        this.authorizationTimeoutSeconds = authorizationTimeoutSeconds;
    }

    public long getCleanupIntervalMs() {
        return cleanupIntervalMs;
    }

    public void setCleanupIntervalMs(long cleanupIntervalMs) {
        this.cleanupIntervalMs = cleanupIntervalMs;
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
}
