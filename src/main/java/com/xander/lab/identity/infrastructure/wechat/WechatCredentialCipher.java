package com.xander.lab.identity.infrastructure.wechat;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * {@code wechat_credential} 表 AppSecret 的 AES-GCM 静态加密器。
 *
 * <p>密钥来自 {@link WechatCredentialProperties#getEncryptionKey()}（Base64 32 字节），
 * 明文永不落库或进入日志。密钥未配置时 {@link #isReady()} 为 false，编译/解密统一
 * 抛出“微信登录未配置”业务错误，保证未接入微信的部署正常启动且通道保持关闭。
 */
@Component
@RequiredArgsConstructor
public class WechatCredentialCipher {

    private final WechatCredentialProperties properties;
    private SecretKeySpec key;
    private boolean ready;

    @PostConstruct
    public void initialize() {
        if (properties.getEncryptionKey() == null || properties.getEncryptionKey().isBlank()) {
            return;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(properties.getEncryptionKey());
            if (bytes.length != 32) throw new IllegalArgumentException();
            key = new SecretKeySpec(bytes, "AES");
            ready = true;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "WECHAT_CREDENTIAL_ENCRYPTION_KEY must be a Base64-encoded 32-byte key");
        }
    }

    public boolean isReady() {
        return ready;
    }

    /** 加密：每次生成随机 IV，输出 {@code Base64(iv).Base64(密文)}。 */
    public String encrypt(String plaintext) {
        requireReady();
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return Base64.getEncoder().encodeToString(iv) + "."
                    + Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法加密微信 AppSecret", e);
        }
    }

    /** 解密 {@link #encrypt(String)} 产生的密文；密钥不匹配时给出明确运维提示。 */
    public String decrypt(String ciphertext) {
        requireReady();
        try {
            String[] parts = ciphertext.split("\\.", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("无法解密微信 AppSecret（加密密钥可能不匹配）", e);
        }
    }

    private void requireReady() {
        if (!ready) {
            throw new IllegalStateException("微信登录未配置，请联系管理员");
        }
    }
}