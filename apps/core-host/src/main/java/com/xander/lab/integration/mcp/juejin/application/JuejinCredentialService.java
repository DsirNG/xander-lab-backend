package com.xander.lab.integration.mcp.juejin.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.integration.api.JuejinCredentialPort;
import com.xander.lab.integration.mcp.juejin.infrastructure.JuejinClient;
import com.xander.lab.integration.mcp.juejin.infrastructure.JuejinPlatformCredential;
import com.xander.lab.integration.mcp.juejin.infrastructure.JuejinPlatformCredentialMapper;
import com.xander.lab.integration.mcp.juejin.infrastructure.JuejinPlatformProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts and persists per-user Juejin browser credentials.
 *
 * <p>Implements {@link JuejinCredentialPort} for the <b>one</b> method another deployment unit
 * calls ({@link #isAuthorized}). Same reasoning as {@code CsdnCredentialService}: the other five
 * public methods have no cross-domain caller. See the port's class comment.</p>
 */
@Service
@RequiredArgsConstructor
public class JuejinCredentialService implements JuejinCredentialPort {
    private final JuejinPlatformCredentialMapper mapper;
    private final JuejinPlatformProperties properties;
    private final ObjectMapper objectMapper;
    private SecretKeySpec key;

    @PostConstruct
    void initialize() {
        if (!properties.isEnabled()) return;
        try {
            byte[] bytes = Base64.getDecoder().decode(properties.getCredentialEncryptionKey());
            if (bytes.length != 32) throw new IllegalArgumentException();
            key = new SecretKeySpec(bytes, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("JUEJIN_CREDENTIAL_ENCRYPTION_KEY must be a Base64-encoded 32-byte key");
        }
    }

    @Override
    public boolean isAuthorized(Long userId) {
        JuejinPlatformCredential credential = find(userId);
        return credential != null && "ACTIVE".equals(credential.getStatus())
                && hasText(credential.getEncryptedCookie());
    }

    /** True when the stored credential was active but juejin rejected it, so re-authorization is required. */
    public boolean isExpired(Long userId) {
        JuejinPlatformCredential credential = find(userId);
        return credential != null && "EXPIRED".equals(credential.getStatus());
    }

    /**
     * Marks an ACTIVE credential EXPIRED when juejin reports an invalid session (HTTP 401/403
     * or body err_no 401). Conditional update: only a currently ACTIVE credential may move to EXPIRED.
     */
    public void markExpired(Long userId) {
        mapper.update(null, new UpdateWrapper<JuejinPlatformCredential>()
                .eq("user_id", userId)
                .eq("status", "ACTIVE")
                .set("status", "EXPIRED"));
    }

    /** Decrypted per-user request context; throws when no ACTIVE credential exists. */
    public JuejinClient.Credential requireCredential(Long userId) {
        JuejinPlatformCredential credential = find(userId);
        if (credential == null || !"ACTIVE".equals(credential.getStatus()) || !hasText(credential.getEncryptedCookie())) {
            throw new IllegalStateException("Juejin authorization is required");
        }
        try { return decrypt(credential.getEncryptedCookie()); }
        catch (Exception e) { throw new IllegalStateException("Unable to read Juejin authorization", e); }
    }

    /** Persists the harvested login credential; concurrent saves for the same user converge on one row. */
    public void save(Long userId, JuejinClient.Credential credential, String username) {
        try {
            JuejinPlatformCredential value = new JuejinPlatformCredential();
            value.setUserId(userId);
            value.setEncryptedCookie(encrypt(credential));
            value.setJuejinUsername(username);
            value.setStatus("ACTIVE");
            mapper.upsert(value);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to save Juejin authorization", e);
        }
    }

    public void delete(Long userId) {
        mapper.delete(new LambdaQueryWrapper<JuejinPlatformCredential>().eq(JuejinPlatformCredential::getUserId, userId));
    }

    private JuejinPlatformCredential find(Long userId) {
        // No status filter here: callers decide between ACTIVE (authorized) and EXPIRED (pending re-authorization).
        return mapper.selectOne(new LambdaQueryWrapper<JuejinPlatformCredential>()
                .eq(JuejinPlatformCredential::getUserId, userId));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String encrypt(JuejinClient.Credential credential) throws Exception {
        byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        String plaintext = objectMapper.writeValueAsString(credential);
        return Base64.getEncoder().encodeToString(iv) + "." + Base64.getEncoder().encodeToString(
                cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
    }

    private JuejinClient.Credential decrypt(String value) throws Exception {
        String[] parts = value.split("\\.", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
        String plaintext = new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        return objectMapper.readValue(plaintext, JuejinClient.Credential.class);
    }
}
