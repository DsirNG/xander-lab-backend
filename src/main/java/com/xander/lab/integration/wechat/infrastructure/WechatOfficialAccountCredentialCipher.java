package com.xander.lab.integration.wechat.infrastructure;

import com.xander.lab.integration.wechat.WechatOfficialAccountProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-GCM cipher for user-owned official-account secrets and access tokens. */
@Component
@RequiredArgsConstructor
public class WechatOfficialAccountCredentialCipher {
    private final WechatOfficialAccountProperties properties;
    private SecretKeySpec key;
    private boolean ready;

    @PostConstruct
    void initialize() {
        if (properties.getCredentialEncryptionKey() == null || properties.getCredentialEncryptionKey().isBlank()) {
            return;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(properties.getCredentialEncryptionKey());
            if (bytes.length != 32) throw new IllegalArgumentException();
            key = new SecretKeySpec(bytes, "AES");
            ready = true;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "WECHAT_OFFICIAL_CREDENTIAL_ENCRYPTION_KEY must be a Base64-encoded 32-byte key");
        }
    }

    public boolean isReady() {
        return ready;
    }

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
            throw new IllegalStateException("无法加密微信公众号凭据", e);
        }
    }

    public String decrypt(String ciphertext) {
        requireReady();
        try {
            String[] parts = ciphertext.split("\\.", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("无法解密微信公众号凭据（加密密钥可能不匹配）", e);
        }
    }

    private void requireReady() {
        if (!ready) {
            throw new IllegalStateException("微信公众号加密密钥未配置");
        }
    }
}
