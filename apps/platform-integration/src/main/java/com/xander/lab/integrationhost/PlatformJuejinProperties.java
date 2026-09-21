package com.xander.lab.integration.host;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Juejin settings owned by the Platform deployment unit. */
@Component
@ConfigurationProperties(prefix = "platform-integration.juejin")
public class PlatformJuejinProperties {

    private String baseUrl = "https://api.juejin.cn";
    private String defaultAid = "2608";
    private String credentialEncryptionKey = "";
    private int connectTimeoutMillis = 10_000;
    private int readTimeoutMillis = 30_000;
    private int catalogFreshnessHours = 24;
    private String seleniumUrl = "http://selenium:4444/wd/hub";
    private int authorizationTimeoutSeconds = 60;
    private long cleanupIntervalMs = 15_000;
    private String qrImageSelector = ".qrcode-img-wrap img.qrcode-img";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getDefaultAid() {
        return defaultAid;
    }

    public void setDefaultAid(String defaultAid) {
        this.defaultAid = defaultAid;
    }

    public String getCredentialEncryptionKey() {
        return credentialEncryptionKey;
    }

    public void setCredentialEncryptionKey(String credentialEncryptionKey) {
        this.credentialEncryptionKey = credentialEncryptionKey;
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

    public int getCatalogFreshnessHours() {
        return catalogFreshnessHours;
    }

    public void setCatalogFreshnessHours(int catalogFreshnessHours) {
        this.catalogFreshnessHours = catalogFreshnessHours;
    }

    public String getSeleniumUrl() {
        return seleniumUrl;
    }

    public void setSeleniumUrl(String seleniumUrl) {
        this.seleniumUrl = seleniumUrl;
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

    public String getQrImageSelector() {
        return qrImageSelector;
    }

    public void setQrImageSelector(String qrImageSelector) {
        this.qrImageSelector = qrImageSelector;
    }
}
