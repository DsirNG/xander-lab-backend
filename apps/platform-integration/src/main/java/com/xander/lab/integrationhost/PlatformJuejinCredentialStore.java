package com.xander.lab.integration.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.Base64;

/** Platform-owned AES-GCM persistence for the per-user Juejin browser credential. */
@Component
@ConditionalOnProperty(prefix = "platform-integration", name = "authorization-enabled",
        havingValue = "true")
public class PlatformJuejinCredentialStore {

    private static final Logger log = LoggerFactory.getLogger(PlatformJuejinCredentialStore.class);
    private static final String SELECT = "SELECT status, encrypted_cookie, juejin_username "
            + "FROM juejin_platform_credential WHERE user_id = ?";
    private static final String UPSERT = "INSERT INTO juejin_platform_credential "
            + "(user_id, encrypted_cookie, juejin_username, status, authorized_at, last_validated_at) "
            + "VALUES (?, ?, ?, 'ACTIVE', ?, ?) AS new ON DUPLICATE KEY UPDATE "
            + "encrypted_cookie = new.encrypted_cookie, juejin_username = new.juejin_username, "
            + "status = new.status, authorized_at = new.authorized_at, "
            + "last_validated_at = new.last_validated_at";

    private final PlatformIntegrationProperties integrationProperties;
    private final PlatformJuejinProperties properties;
    private final ObjectMapper objectMapper;
    private SecretKeySpec key;

    public PlatformJuejinCredentialStore(PlatformIntegrationProperties integrationProperties,
                                         PlatformJuejinProperties properties,
                                         ObjectMapper objectMapper) {
        if (blank(integrationProperties.getCredentialStoreUrl())
                || blank(integrationProperties.getCredentialStoreUsername())) {
            throw new IllegalStateException(
                    "platform-integration Juejin authorization requires credential store URL and username");
        }
        this.integrationProperties = integrationProperties;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void initialize() {
        try {
            byte[] bytes = Base64.getDecoder().decode(properties.getCredentialEncryptionKey());
            if (bytes.length != 32) {
                throw new IllegalArgumentException();
            }
            key = new SecretKeySpec(bytes, "AES");
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "JUEJIN_CREDENTIAL_ENCRYPTION_KEY must be a Base64-encoded 32-byte key");
        }
    }

    /** Reads only the public authorization state, never the decrypted browser context. */
    public CredentialStatus status(Long userId) {
        if (userId == null || userId <= 0) {
            return CredentialStatus.NOT_AUTHORIZED;
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(SELECT)) {
            statement.setQueryTimeout(queryTimeout());
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return CredentialStatus.NOT_AUTHORIZED;
                }
                String status = result.getString(1);
                if ("EXPIRED".equals(status)) {
                    return CredentialStatus.EXPIRED;
                }
                return "ACTIVE".equals(status) && hasText(result.getString(2))
                        ? CredentialStatus.AUTHORIZED : CredentialStatus.NOT_AUTHORIZED;
            }
        } catch (Exception exception) {
            log.warn("[PlatformCredential] Juejin status lookup failed closed userId={} reason={}",
                    userId, exception.getMessage());
            return CredentialStatus.NOT_AUTHORIZED;
        }
    }

    /** Encrypts and persists the harvested browser context; profile fields are non-secret metadata. */
    public void save(Long userId, PlatformJuejinClient.Credential credential, String username) {
        if (userId == null || userId <= 0 || credential == null || !hasText(credential.cookie())) {
            throw new IllegalArgumentException("Juejin credential payload is incomplete");
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(UPSERT)) {
            LocalDateTime now = LocalDateTime.now();
            statement.setLong(1, userId);
            statement.setString(2, encrypt(credential));
            statement.setString(3, username);
            statement.setObject(4, now);
            statement.setObject(5, now);
            statement.executeUpdate();
        } catch (Exception exception) {
            throw new IllegalStateException("保存掘金授权失败", exception);
        }
    }

    /** Deletes the user's Juejin credential after an explicit disconnect command. */
    public void delete(Long userId) {
        if (userId == null || userId <= 0) {
            return;
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM juejin_platform_credential WHERE user_id = ?")) {
            statement.setLong(1, userId);
            statement.executeUpdate();
        } catch (Exception exception) {
            throw new IllegalStateException("删除掘金授权失败", exception);
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(integrationProperties.getCredentialStoreUrl(),
                integrationProperties.getCredentialStoreUsername(),
                integrationProperties.getCredentialStorePassword());
    }

    private int queryTimeout() {
        return Math.max(1, integrationProperties.getCredentialStoreQueryTimeoutSeconds());
    }

    private String encrypt(PlatformJuejinClient.Credential credential) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return Base64.getEncoder().encodeToString(iv) + "."
                + Base64.getEncoder().encodeToString(cipher.doFinal(
                objectMapper.writeValueAsString(credential).getBytes(StandardCharsets.UTF_8)));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public enum CredentialStatus {
        AUTHORIZED,
        EXPIRED,
        NOT_AUTHORIZED
    }
}
