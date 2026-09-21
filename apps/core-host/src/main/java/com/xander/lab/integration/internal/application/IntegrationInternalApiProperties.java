package com.xander.lab.integration.internal.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Integration domain's token-protected Core compatibility boundary settings. */
@Component
@ConfigurationProperties(prefix = "integration.internal")
public class IntegrationInternalApiProperties {

    private boolean apiEnabled;
    private String internalToken = "";

    public boolean isApiEnabled() {
        return apiEnabled;
    }

    public void setApiEnabled(boolean apiEnabled) {
        this.apiEnabled = apiEnabled;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }
}
