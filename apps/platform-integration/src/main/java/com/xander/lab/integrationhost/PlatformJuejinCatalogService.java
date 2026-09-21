package com.xander.lab.integration.host;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Platform-owned Juejin catalog synchronization workflow. */
@Service
@ConditionalOnProperty(prefix = "platform-integration", name = "juejin-catalog-enabled",
        havingValue = "true")
public class PlatformJuejinCatalogService {

    public static final long DEFAULT_CATEGORY_ID = 6809637767543259144L;
    public static final long DEFAULT_TAG_ID = 6809640407484334093L;

    private static final int TAG_PAGE_SIZE = 100;
    private static final int MAX_TAG_PAGES = 10;

    private final PlatformJuejinProperties properties;
    private final PlatformJuejinClient client;
    private final PlatformJuejinCredentialReader credentialReader;
    private final PlatformJuejinCatalogStore store;

    public PlatformJuejinCatalogService(PlatformJuejinProperties properties,
                                        PlatformJuejinClient client,
                                        PlatformJuejinCredentialReader credentialReader,
                                        PlatformJuejinCatalogStore store) {
        this.properties = properties;
        this.client = client;
        this.credentialReader = credentialReader;
        this.store = store;
    }

    public void sync(Long userId) {
        PlatformJuejinClient.Credential credential = credentialReader.requireCredential(userId);
        List<PlatformJuejinCatalogStore.CategoryRow> categories = new ArrayList<>();
        List<PlatformJuejinCatalogStore.TagRow> tags = new ArrayList<>();
        JsonNode categoryResponse = client.queryCategories(credential);
        for (JsonNode item : categoryResponse.path("data")) {
            JsonNode category = item.path("category");
            if (category.isMissingNode() || category.isNull()) {
                continue;
            }
            long categoryId = category.path("category_id").asLong();
            categories.add(new PlatformJuejinCatalogStore.CategoryRow(
                    categoryId, category.path("category_name").asText(""),
                    category.path("rank").asInt(0)));
            for (JsonNode hot : item.path("hot_tags")) {
                tags.add(new PlatformJuejinCatalogStore.TagRow(
                        hot.path("tag_id").asLong(), hot.path("tag_name").asText(""), categoryId));
            }
        }
        String cursor = "0";
        for (int page = 0; page < MAX_TAG_PAGES; page++) {
            JsonNode response = client.queryTagList(credential, cursor, TAG_PAGE_SIZE);
            for (JsonNode item : response.path("data")) {
                tags.add(new PlatformJuejinCatalogStore.TagRow(
                        item.path("tag_id").asLong(), item.path("tag").path("tag_name").asText(""), null));
            }
            if (!response.path("has_more").asBoolean(false)) {
                break;
            }
            cursor = response.path("cursor").asText(cursor);
        }
        store.upsert(categories, tags);
    }

    public void ensureFresh(Long userId) {
        if (!store.isFresh(properties.getCatalogFreshnessHours())) {
            sync(userId);
        }
    }

    public boolean isFresh() {
        return store.isFresh(properties.getCatalogFreshnessHours());
    }
}
