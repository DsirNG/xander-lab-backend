package com.xander.lab.identity.infrastructure.wechat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 微信小程序登录相关配置。
 *
 * <p>来自环境变量（生产）或本地配置，凭据不落入源码。三项均未配置时微信登录通道
 * 默认关闭，登录接口返回明确的业务错误而非启动失败，保证未接入微信的平台不受影响。
 */
@Component
@ConfigurationProperties(prefix = "wechat.miniapp")
public class WechatMiniAppProperties {

    /** 微信小程序 AppID */
    private String appId = "";

    /** 微信小程序 AppSecret（仅用于服务端换取 openid，绝不随响应或日志外泄） */
    private String appSecret = "";

    /** jscode2session 接口地址，默认官方地址，可覆盖以支持代理 */
    private String code2SessionUrl = "https://api.weixin.qq.com/sns/jscode2session";

    /** 连接超时（毫秒） */
    private long connectTimeoutMillis = 5000;

    /** 读取超时（毫秒）；微信接口响应小，5 秒足够 */
    private long readTimeoutMillis = 5000;

    /** 微信通道是否已配置可用 */
    public boolean isConfigured() {
        return !appId.isBlank() && !appSecret.isBlank();
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

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