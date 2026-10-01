package com.xander.lab.integration.wechat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Runtime switches and network settings for the first-version WeChat publisher. */
@Data
@Component
@ConfigurationProperties(prefix = "wechat.official")
public class WechatOfficialAccountProperties {
    /** Disabled by default so an environment without the migration/key stays safe. */
    private boolean enabled;
    private String apiBaseUrl = "https://api.weixin.qq.com";
    private String credentialEncryptionKey;
    private int connectTimeoutMillis = 5000;
    private int readTimeoutMillis = 15000;
}
