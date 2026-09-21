package com.xander.lab.integration.host;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;

/** Platform-owned persistence adapter for the shared Juejin category/tag catalog. */
@Component
@ConditionalOnProperty(prefix = "platform-integration", name = "juejin-catalog-enabled",
        havingValue = "true")
public class PlatformJuejinCatalogStore {

    private static final String CATEGORY_UPSERT = """
            INSERT INTO juejin_category (category_id, category_name, `rank`)
            VALUES (?, ?, ?)
            AS new ON DUPLICATE KEY UPDATE
                category_name = new.category_name,
                `rank` = new.`rank`
            """;
    private static final String TAG_UPSERT = """
            INSERT INTO juejin_tag (tag_id, tag_name, category_id)
            VALUES (?, ?, ?)
            AS new ON DUPLICATE KEY UPDATE
                tag_name = new.tag_name,
                category_id = IFNULL(new.category_id, juejin_tag.category_id)
            """;

    private final PlatformIntegrationProperties properties;

    public PlatformJuejinCatalogStore(PlatformIntegrationProperties properties) {
        this.properties = properties;
    }

    public void upsert(List<CategoryRow> categories, List<TagRow> tags) {
        try (Connection connection = connection();
             PreparedStatement categoryStatement = connection.prepareStatement(CATEGORY_UPSERT);
             PreparedStatement tagStatement = connection.prepareStatement(TAG_UPSERT)) {
            connection.setAutoCommit(false);
            for (CategoryRow category : categories) {
                categoryStatement.setLong(1, category.categoryId());
                categoryStatement.setString(2, category.categoryName());
                categoryStatement.setInt(3, category.rank());
                categoryStatement.addBatch();
            }
            for (TagRow tag : tags) {
                tagStatement.setLong(1, tag.tagId());
                tagStatement.setString(2, tag.tagName());
                if (tag.categoryId() == null) {
                    tagStatement.setObject(3, null);
                } else {
                    tagStatement.setLong(3, tag.categoryId());
                }
                tagStatement.addBatch();
            }
            categoryStatement.executeBatch();
            tagStatement.executeBatch();
            connection.commit();
        } catch (Exception exception) {
            throw new IllegalStateException("保存掘金目录失败", exception);
        }
    }

    public boolean isFresh(int freshnessHours) {
        LocalDateTime category = latest("juejin_category");
        LocalDateTime tag = latest("juejin_tag");
        if (category == null || tag == null) {
            return false;
        }
        return category.isAfter(LocalDateTime.now().minusHours(freshnessHours))
                && tag.isAfter(LocalDateTime.now().minusHours(freshnessHours));
    }

    /** Resolves a local category name without contacting Juejin. */
    public String findCategoryId(String categoryName) {
        if (categoryName == null || categoryName.isBlank()) {
            return null;
        }
        return findId("SELECT category_id FROM juejin_category WHERE category_name = ? LIMIT 1",
                categoryName.strip());
    }

    /** Resolves a local tag name without contacting Juejin. */
    public String findTagId(String tagName) {
        if (tagName == null || tagName.isBlank()) {
            return null;
        }
        return findId("SELECT tag_id FROM juejin_tag WHERE tag_name = ? LIMIT 1", tagName.strip());
    }

    private LocalDateTime latest(String table) {
        if (!"juejin_category".equals(table) && !"juejin_tag".equals(table)) {
            throw new IllegalArgumentException("unsupported catalog table");
        }
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT MAX(updated_at) FROM " + table);
             ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getTimestamp(1) == null
                    ? null : result.getTimestamp(1).toLocalDateTime() : null;
        } catch (Exception exception) {
            throw new IllegalStateException("读取掘金目录新鲜度失败", exception);
        }
    }

    private String findId(String sql, String value) {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        } catch (Exception exception) {
            throw new IllegalStateException("读取掘金目录失败", exception);
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(properties.getCredentialStoreUrl(),
                properties.getCredentialStoreUsername(), properties.getCredentialStorePassword());
    }

    public record CategoryRow(long categoryId, String categoryName, int rank) {
    }

    public record TagRow(long tagId, String tagName, Long categoryId) {
    }
}
