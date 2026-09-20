package com.xander.lab.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Gateway 安全事件快路径配置；默认关闭，避免本地开发强制依赖 Redis。 */
@Component
@ConfigurationProperties(prefix = "gateway.security-state")
public class GatewaySecurityStateProperties {

    private boolean pubsubEnabled = false;
    private String pubsubChannel = "dinqor:security-events";

    public boolean isPubsubEnabled() {
        return pubsubEnabled;
    }

    public void setPubsubEnabled(boolean pubsubEnabled) {
        this.pubsubEnabled = pubsubEnabled;
    }

    public String getPubsubChannel() {
        return pubsubChannel;
    }

    public void setPubsubChannel(String pubsubChannel) {
        this.pubsubChannel = pubsubChannel;
    }
}
