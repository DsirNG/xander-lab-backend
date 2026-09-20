package com.xander.lab.agent.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Agent 调用 core-host 内网契约 API 的部署配置。 */
@Component
@ConfigurationProperties(prefix = "agent.core-client")
public class AgentCoreClientProperties {

    private boolean enabled;
    private String baseUrl = "http://core-host:30002";
    private String internalToken = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }
}
