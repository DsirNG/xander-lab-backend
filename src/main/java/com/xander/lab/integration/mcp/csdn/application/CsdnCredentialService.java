package com.xander.lab.integration.mcp.csdn.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xander.lab.integration.mcp.csdn.infrastructure.CsdnPlatformCredential;
import com.xander.lab.integration.mcp.csdn.infrastructure.CsdnPlatformCredentialMapper;
import com.xander.lab.integration.mcp.csdn.infrastructure.CsdnProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

/** Encrypts and persists per-user CSDN browser credentials. */
@Service
@RequiredArgsConstructor
public class CsdnCredentialService {
    private final CsdnPlatformCredentialMapper mapper;
    private final CsdnProperties properties;
    private SecretKeySpec key;

    @PostConstruct
    void initialize() {
        if (!properties.isEnabled()) return;
        try {
            byte[] bytes = Base64.getDecoder().decode(properties.getCredentialEncryptionKey());
            if (bytes.length != 32) throw new IllegalArgumentException();
            key = new SecretKeySpec(bytes, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("CSDN_CREDENTIAL_ENCRYPTION_KEY must be a Base64-encoded 32-byte key");
        }
    }

    public boolean isAuthorized(Long userId) {
        CsdnPlatformCredential credential = find(userId);
        return credential != null
                && hasText(credential.getEncryptedCookie())
                && hasText(credential.getCsdnUsername());
    }

    public String requireCookie(Long userId) {
        CsdnPlatformCredential credential = find(userId);
        if (credential == null || !hasText(credential.getEncryptedCookie()) || !hasText(credential.getCsdnUsername())) {
            throw new IllegalStateException("CSDN authorization is required");
        }
        try { return decrypt(credential.getEncryptedCookie()); }
        catch (Exception e) { throw new IllegalStateException("Unable to read CSDN authorization", e); }
    }

    public void save(Long userId, String cookie, String username) {
        try {
            CsdnPlatformCredential value = find(userId);
            LocalDateTime now = LocalDateTime.now();
            if (value == null) { value = new CsdnPlatformCredential(); value.setUserId(userId); value.setCreatedAt(now); }
            value.setEncryptedCookie(encrypt(cookie)); value.setCsdnUsername(username); value.setStatus("ACTIVE");
            value.setAuthorizedAt(now); value.setLastValidatedAt(now); value.setUpdatedAt(now);
            if (value.getId() == null) mapper.insert(value); else mapper.updateById(value);
        } catch (Exception e) { throw new IllegalStateException("Unable to save CSDN authorization", e); }
    }

    public void delete(Long userId) {
        mapper.delete(new LambdaQueryWrapper<CsdnPlatformCredential>().eq(CsdnPlatformCredential::getUserId, userId));
    }

    private CsdnPlatformCredential find(Long userId) {
        return mapper.selectOne(new LambdaQueryWrapper<CsdnPlatformCredential>()
                .eq(CsdnPlatformCredential::getUserId, userId).eq(CsdnPlatformCredential::getStatus, "ACTIVE"));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String encrypt(String plaintext) throws Exception {
        byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return Base64.getEncoder().encodeToString(iv) + "." + Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
    }

    private String decrypt(String value) throws Exception {
        String[] parts = value.split("\\.", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
        return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
    }
}
