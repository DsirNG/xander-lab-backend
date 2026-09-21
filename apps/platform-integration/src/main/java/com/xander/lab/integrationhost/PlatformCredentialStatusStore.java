package com.xander.lab.integration.host;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Platform-owned, read-only credential status boundary used during the ownership cutover.
 *
 * <p>The store deliberately selects only status and non-secret presence columns. It never
 * decrypts or returns a platform credential. The feature is opt-in so the compatibility
 * deployment can run without a database while the tables are still drained from Core.</p>
 */
@Component
@ConditionalOnProperty(prefix = "platform-integration", name = "credential-store-enabled",
        havingValue = "true")
public class PlatformCredentialStatusStore {

    private static final Logger log = LoggerFactory.getLogger(PlatformCredentialStatusStore.class);

    private final PlatformIntegrationProperties properties;

    public PlatformCredentialStatusStore(PlatformIntegrationProperties properties) {
        this.properties = properties;
        if (blank(properties.getCredentialStoreUrl())
                || blank(properties.getCredentialStoreUsername())) {
            throw new IllegalStateException(
                    "platform-integration credential store requires URL and username");
        }
    }

    /** Returns false on a missing row or any database failure (publishing must fail closed). */
    public boolean isAuthorized(String platform, Long userId) {
        return status(platform, userId) == CredentialStatus.AUTHORIZED;
    }

    /**
     * Reads the public-safe authorization state without loading or decrypting a credential.
     * Database failures deliberately become NOT_AUTHORIZED so a publishing caller cannot
     * mistake an unavailable credential boundary for an authorized account.
     */
    public CredentialStatus status(String platform, Long userId) {
        if (userId == null || userId <= 0) {
            return CredentialStatus.NOT_AUTHORIZED;
        }
        String sql = switch (platform) {
            case "csdn" -> "SELECT status, encrypted_cookie, csdn_username "
                    + "FROM csdn_platform_credential WHERE user_id = ?";
            case "juejin" -> "SELECT status, encrypted_cookie, juejin_username "
                    + "FROM juejin_platform_credential WHERE user_id = ?";
            default -> throw new IllegalArgumentException("unsupported platform: " + platform);
        };
        try (Connection connection = DriverManager.getConnection(
                properties.getCredentialStoreUrl(),
                properties.getCredentialStoreUsername(),
                properties.getCredentialStorePassword());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(Math.max(1, properties.getCredentialStoreQueryTimeoutSeconds()));
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return CredentialStatus.NOT_AUTHORIZED;
                }
                if ("EXPIRED".equals(result.getString(1))) {
                    return CredentialStatus.EXPIRED;
                }
                boolean complete = "ACTIVE".equals(result.getString(1))
                        && hasText(result.getString(2))
                        && hasText(result.getString(3));
                return complete ? CredentialStatus.AUTHORIZED : CredentialStatus.NOT_AUTHORIZED;
            }
        } catch (SQLException exception) {
            log.warn("[PlatformCredential] status lookup fail-closed platform={} userId={} reason={}",
                    platform, userId, exception.getMessage());
            return CredentialStatus.NOT_AUTHORIZED;
        }
    }

    public enum CredentialStatus {
        AUTHORIZED,
        EXPIRED,
        NOT_AUTHORIZED
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
