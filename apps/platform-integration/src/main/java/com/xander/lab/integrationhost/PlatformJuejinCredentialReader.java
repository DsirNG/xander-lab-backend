package com.xander.lab.integration.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Base64;

/** Reads and decrypts only the Juejin credential needed by the catalog sync. */
@Component
@ConditionalOnProperty(prefix = "platform-integration", name = "juejin-catalog-enabled",
        havingValue = "true")
public class PlatformJuejinCredentialReader {

    private final PlatformIntegrationProperties integrationProperties;
    private final PlatformJuejinProperties juejinProperties;
    private final ObjectMapper objectMapper;
    private SecretKeySpec key;

    public PlatformJuejinCredentialReader(PlatformIntegrationProperties integrationProperties,
                                          PlatformJuejinProperties juejinProperties,
                                          ObjectMapper objectMapper) {
        if (blank(integrationProperties.getCredentialStoreUrl())
                || blank(integrationProperties.getCredentialStoreUsername())) {
            throw new IllegalStateException(
                    "platform-integration Juejin catalog requires credential store URL and username");
        }
        this.integrationProperties = integrationProperties;
        this.juejinProperties = juejinProperties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void initialize() {
        try {
            byte[] bytes = Base64.getDecoder().decode(juejinProperties.getCredentialEncryptionKey());
            if (bytes.length != 32) {
                throw new IllegalArgumentException();
            }
            key = new SecretKeySpec(bytes, "AES");
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "JUEJIN_CREDENTIAL_ENCRYPTION_KEY must be a Base64-encoded 32-byte key");
        }
    }

    public PlatformJuejinClient.Credential requireCredential(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("凭证属主参数非法");
        }
        String encryptedCookie = null;
        String status = null;
        String sql = "SELECT status, encrypted_cookie FROM juejin_platform_credential WHERE user_id = ?";
        try (Connection connection = DriverManager.getConnection(
                integrationProperties.getCredentialStoreUrl(),
                integrationProperties.getCredentialStoreUsername(),
                integrationProperties.getCredentialStorePassword());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(Math.max(1, integrationProperties.getCredentialStoreQueryTimeoutSeconds()));
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    status = result.getString(1);
                    encryptedCookie = result.getString(2);
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException("读取掘金凭证失败", exception);
        }
        if (!"ACTIVE".equals(status) || blank(encryptedCookie)) {
            throw new IllegalStateException("Juejin authorization is required");
        }
        try {
            return decrypt(encryptedCookie);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read Juejin authorization", exception);
        }
    }

    private PlatformJuejinClient.Credential decrypt(String value) throws Exception {
        String[] parts = value.split("\\.", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("invalid encrypted credential");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
        String plaintext = new String(
                cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        return objectMapper.readValue(plaintext, PlatformJuejinClient.Credential.class);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
