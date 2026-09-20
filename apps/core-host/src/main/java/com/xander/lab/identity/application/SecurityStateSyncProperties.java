package com.xander.lab.identity.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** core-host 安全状态同步内网 API 的开关与共享令牌。 */
@Component
@ConfigurationProperties(prefix = "security.state-sync")
public class SecurityStateSyncProperties {

    private boolean internalApiEnabled;
    private String internalToken = "";

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
}
