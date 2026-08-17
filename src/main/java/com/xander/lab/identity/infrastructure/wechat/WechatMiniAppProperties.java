package com.xander.lab.identity.infrastructure.wechat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 微信小程序登录通道的传输配置（接口地址与超时）。
 *
 * <p>AppID 与 AppSecret 不再来自本配置：由管理台加密维护（{@code wechat_credential} 表，
 * 经 WechatCredentialService 读取），避免凭据进入环境变量或源码。
 */
@Component
@ConfigurationProperties(prefix = "wechat.miniapp")
public class WechatMiniAppProperties {

    /** jscode2session 接口地址，默认官方地址，可覆盖以支持代理 */
    private String code2SessionUrl = "https://api.weixin.qq.com/sns/jscode2session";

    /** 连接超时（毫秒） */
    private long connectTimeoutMillis = 5000;

    /** 读取超时（毫秒）；微信接口响应小，5 秒足够 */
    private long readTimeoutMillis = 5000;

    public String getCode2SessionUrl() {
        return code2SessionUrl;
    }

    public void setCode2SessionUrl(String code2SessionUrl) {
        this.code2SessionUrl = code2SessionUrl;
    }

    public long getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public void setConnectTimeoutMillis(long connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    public long getReadTimeoutMillis() {
        return readTimeoutMillis;
    }

    public void setReadTimeoutMillis(long readTimeoutMillis) {
        this.readTimeoutMillis = readTimeoutMillis;
    }
}