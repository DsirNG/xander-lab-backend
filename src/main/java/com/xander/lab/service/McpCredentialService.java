package com.xander.lab.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.config.McpBlogProperties;
import com.xander.lab.dto.auth.TokenResponse;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class McpCredentialService {
    private static final String REDIS_KEY = "mcp:blog:credentials";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final McpBlogProperties properties;
    private SecretKeySpec key;

    @PostConstruct
    void initialize() {
        if (!properties.isEnabled()) return;
        try {
            byte[] bytes = Base64.getDecoder().decode(properties.getCredentialEncryptionKey());
            if (bytes.length != 32) throw new IllegalArgumentException();
            key = new SecretKeySpec(bytes, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("MCP_CREDENTIAL_ENCRYPTION_KEY must be a Base64-encoded 32-byte key");
        }
        if (properties.getServerToken() == null || properties.getServerToken().isBlank()) {
            throw new IllegalStateException("MCP_SERVER_TOKEN must be configured when MCP is enabled");
        }
    }

    public void save(TokenResponse tokenResponse) {
        try {
            redisTemplate.opsForValue().set(REDIS_KEY, encrypt(objectMapper.writeValueAsString(tokenResponse)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to save MCP authorization", e);
        }
    }

    public TokenResponse load() {
        String value = redisTemplate.opsForValue().get(REDIS_KEY);
        if (value == null) return null;
        try {
            return objectMapper.readValue(decrypt(value), TokenResponse.class);
        } catch (Exception e) {
            redisTemplate.delete(REDIS_KEY);
            return null;
        }
    }

    public void clear() { redisTemplate.delete(REDIS_KEY); }

    private String encrypt(String plaintext) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(iv) + "." + Base64.getEncoder().encodeToString(encrypted);
    }

    private String decrypt(String value) throws Exception {
        String[] parts = value.split("\\.", 2);
        if (parts.length != 2) throw new IllegalArgumentException("Invalid encrypted credential");
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
        return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), java.nio.charset.StandardCharsets.UTF_8);
    }
}
