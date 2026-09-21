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
        if (userId == null || userId <= 0) {
            return false;
        }
        String sql = switch (platform) {
            case "csdn" -> "SELECT status FROM csdn_platform_credential "
                    + "WHERE user_id = ? AND status = 'ACTIVE' "
                    + "AND encrypted_cookie IS NOT NULL AND encrypted_cookie <> '' "
                    + "AND csdn_username IS NOT NULL AND TRIM(csdn_username) <> ''";
            case "juejin" -> "SELECT status FROM juejin_platform_credential "
                    + "WHERE user_id = ? AND status = 'ACTIVE' "
                    + "AND encrypted_cookie IS NOT NULL AND encrypted_cookie <> '' "
                    + "AND juejin_username IS NOT NULL AND TRIM(juejin_username) <> ''";
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
                return result.next() && "ACTIVE".equals(result.getString(1));
            }
        } catch (SQLException exception) {
            log.warn("[PlatformCredential] status lookup fail-closed platform={} userId={} reason={}",
                    platform, userId, exception.getMessage());
            return false;
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
