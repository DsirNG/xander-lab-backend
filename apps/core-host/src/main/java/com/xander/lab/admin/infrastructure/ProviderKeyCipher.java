package com.xander.lab.admin.infrastructure;

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
 * AES-GCM cipher for model provider API keys at rest.
 * Keyed by {@link ModelGatewayProperties#getEncryptionKey()} (Base64, 32 bytes)
 * so the plaintext never touches the database or logs. When the gateway is
 * disabled the key is optional and this component stays inactive.
 */
@Component
@RequiredArgsConstructor
public class ProviderKeyCipher {

    private final ModelGatewayProperties properties;
    private SecretKeySpec key;

    @PostConstruct
    public void initialize() {
        if (!properties.isEnabled()) return;
        try {
            byte[] bytes = Base64.getDecoder().decode(properties.getEncryptionKey());
            if (bytes.length != 32) throw new IllegalArgumentException();
            key = new SecretKeySpec(bytes, "AES");
        } catch (Exception e) {
            throw new IllegalStateException(
                    "MODEL_PROVIDER_ENCRYPTION_KEY must be a Base64-encoded 32-byte key"
                            + " (required while admin.model-gateway.enabled=true)");
        }
    }

    /** Encrypts with a fresh random IV; output is {@code Base64(iv).Base64(ciphertext)}. */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return Base64.getEncoder().encodeToString(iv) + "."
                    + Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法加密模型供应商 API 密钥", e);
        }
    }

    /** Decrypts a value produced by {@link #encrypt(String)}. */
    public String decrypt(String ciphertext) {
        try {
            String[] parts = ciphertext.split("\\.", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("无法解密模型供应商 API 密钥", e);
        }
    }
}