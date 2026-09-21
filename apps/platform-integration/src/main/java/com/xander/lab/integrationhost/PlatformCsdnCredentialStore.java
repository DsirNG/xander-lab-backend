package com.xander.lab.integration.host;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

/**
 * Platform-owned CSDN credential persistence used by the local authorization workflow.
 *
 * <p>Only the encrypted browser cookie is persisted; the store never returns credential material
 * to an HTTP controller. The feature is opt-in until the Platform database grant and key Secret
 * are staged.</p>
 */
@Component
@ConditionalOnProperty(prefix = "platform-integration", name = "authorization-enabled",
        havingValue = "true")
public class PlatformCsdnCredentialStore {

    private static final Logger log = LoggerFactory.getLogger(PlatformCsdnCredentialStore.class);

    private static final String SELECT = "SELECT status, encrypted_cookie, csdn_username "
            + "FROM csdn_platform_credential WHERE user_id = ?";
    private static final String UPSERT = "INSERT INTO csdn_platform_credential "
            + "(user_id, encrypted_cookie, csdn_username, status, authorized_at, last_validated_at) "
            + "VALUES (?, ?, ?, 'ACTIVE', ?, ?) AS new ON DUPLICATE KEY UPDATE "
            + "encrypted_cookie = new.encrypted_cookie, csdn_username = new.csdn_username, "
            + "status = new.status, authorized_at = new.authorized_at, "
            + "last_validated_at = new.last_validated_at";

    private final PlatformIntegrationProperties integrationProperties;
    private final PlatformCsdnProperties properties;
    private SecretKeySpec key;

    public PlatformCsdnCredentialStore(PlatformIntegrationProperties integrationProperties,
                                       PlatformCsdnProperties properties) {
        if (blank(integrationProperties.getCredentialStoreUrl())
                || blank(integrationProperties.getCredentialStoreUsername())) {
            throw new IllegalStateException(
                    "platform-integration CSDN authorization requires credential store URL and username");
        }
        this.integrationProperties = integrationProperties;
        this.properties = properties;
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
                    "CSDN_CREDENTIAL_ENCRYPTION_KEY must be a Base64-encoded 32-byte key");
        }
    }

    /** Reads public authorization state without decrypting the stored cookie. */
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
                return "ACTIVE".equals(status)
                        && hasText(result.getString(2))
                        && hasText(result.getString(3))
                        ? CredentialStatus.AUTHORIZED : CredentialStatus.NOT_AUTHORIZED;
            }
        } catch (Exception exception) {
            // An unavailable credential boundary must never be interpreted as authorization.
            log.warn("[PlatformCredential] CSDN status lookup failed closed userId={} reason={}",
                    userId, exception.getMessage());
            return CredentialStatus.NOT_AUTHORIZED;
        }
    }

    /** Stores a browser cookie encrypted with AES-GCM and marks the credential active. */
    public void save(Long userId, String cookie, String username) {
        if (userId == null || userId <= 0 || !hasText(cookie)) {
            throw new IllegalArgumentException("CSDN credential payload is incomplete");
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(UPSERT)) {
            LocalDateTime now = LocalDateTime.now();
            statement.setLong(1, userId);
            statement.setString(2, encrypt(cookie));
            statement.setString(3, username);
            statement.setObject(4, now);
            statement.setObject(5, now);
            statement.executeUpdate();
        } catch (Exception exception) {
            throw new IllegalStateException("保存 CSDN 授权失败", exception);
        }
    }

    /** Deletes the user's CSDN credential after an explicit disconnect command. */
    public void delete(Long userId) {
        if (userId == null || userId <= 0) {
            return;
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM csdn_platform_credential WHERE user_id = ?")) {
            statement.setLong(1, userId);
            statement.executeUpdate();
        } catch (Exception exception) {
            throw new IllegalStateException("删除 CSDN 授权失败", exception);
        }
    }

    /** Returns the decrypted active browser cookie only to the Platform vendor adapter. */
    public String requireCookie(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("凭证属主参数非法");
        }
        String status = null;
        String encrypted = null;
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(SELECT)) {
            statement.setQueryTimeout(queryTimeout());
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    status = result.getString(1);
                    encrypted = result.getString(2);
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException("读取 CSDN 授权失败", exception);
        }
        if (!"ACTIVE".equals(status) || !hasText(encrypted)) {
            throw new IllegalStateException("CSDN authorization is required");
        }
        try {
            return decrypt(encrypted);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read CSDN authorization", exception);
        }
    }

    /** Moves only an active row to EXPIRED after a provider-authentication failure. */
    public void markExpired(Long userId) {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE csdn_platform_credential SET status = 'EXPIRED' "
                             + "WHERE user_id = ? AND status = 'ACTIVE'")) {
            statement.setLong(1, userId);
            statement.executeUpdate();
        } catch (Exception exception) {
            log.warn("[PlatformCredential] failed to mark CSDN credential expired userId={} reason={}",
                    userId, exception.getMessage());
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

    private String encrypt(String plaintext) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return Base64.getEncoder().encodeToString(iv) + "."
                + Base64.getEncoder().encodeToString(
                cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
    }

    private String decrypt(String value) throws Exception {
        String[] parts = value.split("\\.", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("invalid encrypted credential");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
        return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
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
