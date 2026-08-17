package com.xander.lab.identity.infrastructure.wechat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 微信小程序登录凭据的加密配置。
 *
 * <p>这里只承载加密密钥（环境变量 WECHAT_CREDENTIAL_ENCRYPTION_KEY，Base64 32 字节）。
 * AppID 与 AppSecret 本身由管理台维护、加密落库（{@code wechat_credential} 表），
 * 不再进入启动参数或源码。密钥未配置时（未接入微信的部署）凭据不可读写，
 * 微信登录通道保持关闭且不影响其它登录方式。
 */
@Component
@ConfigurationProperties(prefix = "wechat.credential")
public class WechatCredentialProperties {

    /** AES-GCM 加密密钥（Base64 32 字节）；未配置时微信通道关闭 */
    private String encryptionKey = "";

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }
}