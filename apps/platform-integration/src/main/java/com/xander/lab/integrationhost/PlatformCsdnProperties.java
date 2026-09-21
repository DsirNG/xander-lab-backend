package com.xander.lab.integration.host;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** CSDN authorization settings owned by the Platform deployment unit. */
@Component
@ConfigurationProperties(prefix = "platform-integration.csdn")
public class PlatformCsdnProperties {

    private String seleniumUrl = "http://selenium:4444/wd/hub";
    private String credentialEncryptionKey = "";
    private int authorizationTimeoutSeconds = 35;
    private long cleanupIntervalMs = 15_000;

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
}
