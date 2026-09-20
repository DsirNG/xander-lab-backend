package com.xander.lab.integration.host;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Runtime settings for the temporary Core compatibility bridge. */
@Component
@ConfigurationProperties(prefix = "platform-integration")
public class PlatformIntegrationProperties {

    private String coreBaseUrl = "http://localhost:30002";

    public String getCoreBaseUrl() {
        return coreBaseUrl;
    }

    public void setCoreBaseUrl(String coreBaseUrl) {
        this.coreBaseUrl = coreBaseUrl;
    }
}
