package com.xander.lab.agent.tool.mcp.infrastructure;

import com.xander.lab.agent.tool.mcp.McpClientProperties;
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
 * 远端 MCP 服务器附加请求头的 AES-GCM 加解密。
 *
 * <p>与 {@code ProviderKeyCipher}、{@code CsdnCredentialService} 同一套做法：
 * 输出 {@code Base64(iv).Base64(ciphertext)}，每次加密都用新的随机 IV。
 * 每个集成模块持有自己的密钥，所以这里单独读 {@code mcp.client.credential-encryption-key}，
 * 不复用别人的——一份密钥泄漏不该波及另一条链路。</p>
 */
@Component
@RequiredArgsConstructor
public class McpHeaderCipher {

    private final McpClientProperties properties;

    private SecretKeySpec key;

    @PostConstruct
    public void initialize() {
        if (!properties.isEnabled()) return;
        try {
            byte[] bytes = Base64.getDecoder().decode(properties.getCredentialEncryptionKey());
            if (bytes.length != 32) throw new IllegalArgumentException();
            key = new SecretKeySpec(bytes, "AES");
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "MCP_CLIENT_CREDENTIAL_ENCRYPTION_KEY 必须是 Base64 编码的 32 字节密钥"
                            + "（mcp.client.enabled=true 时必填）");
        }
    }

    /** 用新的随机 IV 加密；输出 {@code Base64(iv).Base64(ciphertext)}。 */
    public String encrypt(String plaintext) {
        requireKey();
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return Base64.getEncoder().encodeToString(iv) + "."
                    + Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法加密 MCP 服务器请求头", exception);
        }
    }

    /** 解密由 {@link #encrypt(String)} 产出的密文。 */
    public String decrypt(String ciphertext) {
        requireKey();
        try {
            String[] parts = ciphertext.split("\\.", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("无法解密 MCP 服务器请求头", exception);
        }
    }

    private void requireKey() {
        if (key == null) throw new IllegalStateException("MCP 客户端未启用，无法读写请求头密文");
    }
}
