package com.xander.lab.identity.application;

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
 * successor refresh token 的静态加密（V9 §38 / §16）。
 *
 * <p>轮换时把新 token 的密文写进 {@code auth_refresh_token_history}，这样 grace
 * 窗口内的第 2、第 3、第 N 次重放都能返回<b>同一个</b> successor，而不需要
 * 重新签发（重新签发会让并发标签页拿到不同 token，进而互相判 theft）。</p>
 *
 * <p>与仓库既有的 5 处密文组件（{@code ProviderKeyCipher} / {@code WechatCredentialCipher} /
 * {@code McpHeaderCipher} 等）保持同一形态：{@code AES/GCM/NoPadding}，
 * 12 字节随机 IV，输出 {@code Base64(iv).Base64(ciphertext)}。</p>
 *
 * <p>密钥来自部署 Secret（{@code AUTH_REFRESH_ENCRYPTION_KEY}，Base64 的 32 字节），
 * <b>不进 Nacos 明文</b>。特性开启而密钥缺失时直接启动失败，不回落到已知默认值。</p>
 */
@Component
@RequiredArgsConstructor
public class SuccessorTokenCipher {

    private final RefreshTokenProperties properties;

    private SecretKeySpec key;

    @PostConstruct
    void initialize() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(properties.getEncryptionKey());
            if (bytes.length != 32) {
                throw new IllegalArgumentException("key length must be 32 bytes");
            }
            key = new SecretKeySpec(bytes, "AES");
        } catch (Exception e) {
            throw new IllegalStateException(
                    "AUTH_REFRESH_ENCRYPTION_KEY 必须是 Base64 编码的 32 字节密钥"
                            + "（auth.refresh.enabled=true 时必填）", e);
        }
    }

    /** 是否可用（特性关闭时不可用，调用方据此跳过密文写入）。 */
    public boolean isActive() {
        return key != null;
    }

    /** 加密，使用全新随机 IV。 */
    public String encrypt(String plaintext) {
        requireActive();
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return Base64.getEncoder().encodeToString(iv) + "."
                    + Base64.getEncoder().encodeToString(
                            cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法加密 refresh successor", e);
        }
    }

    /**
     * 解密。返回 {@code null} 表示不可恢复（密文缺失或已损坏）。
     *
     * <p>调用方必须先自行判断时间窗口（V9 §38：{@code now > successorCiphertextExpiresAt}
     * 之后禁止解密/返回），本方法不做时间判断。</p>
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return null;
        }
        requireActive();
        try {
            String[] parts = ciphertext.split("\\.", 2);
            if (parts.length != 2) {
                return null;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private void requireActive() {
        if (key == null) {
            throw new IllegalStateException(
                    "SuccessorTokenCipher 未激活：auth.refresh.enabled=false 或密钥缺失");
        }
    }
}
