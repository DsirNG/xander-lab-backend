package com.xander.lab.integration.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.xander.lab.integration.api.BlogSyncResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Platform-owned Juejin draft creation and publication workflow. */
@Component
@ConditionalOnProperty(prefix = "platform-integration", name = "authorization-enabled",
        havingValue = "true")
public class PlatformJuejinPublisher {

    static final int MIN_BRIEF_LENGTH = 50;
    static final int MAX_BRIEF_LENGTH = 100;
    static final int MAX_TAGS = 3;
    private static final Pattern MARKDOWN_PREFIX = Pattern.compile("^[#>*\\-+\\d.\\s]+");
    private static final Pattern NUMERIC_ID = Pattern.compile("\\d+");

    private final PlatformJuejinProperties properties;
    private final PlatformJuejinCredentialStore credentials;
    private final PlatformJuejinClient client;
    private final Optional<PlatformJuejinCatalogService> catalog;

    public PlatformJuejinPublisher(PlatformJuejinProperties properties,
                                   PlatformJuejinCredentialStore credentials,
                                   PlatformJuejinClient client,
                                   Optional<PlatformJuejinCatalogService> catalog) {
        this.properties = properties;
        this.credentials = credentials;
        this.client = client;
        this.catalog = catalog;
    }

    public BlogSyncResult publish(PublishRequest request) {
        validate(request);
        PlatformJuejinClient.Credential credential = credentials.requireCredential(request.userId());
        String categoryId = resolveCategory(request);
        List<String> tagIds = resolveTags(request.tags());
        String brief = buildBrief(request.summary(), request.content());
        try {
            JsonNode created = client.createDraft(credential, request.title(), brief, request.content(),
                    categoryId, tagIds, request.coverImage());
            String draftId = textValue(created.path("data"), "id");
            if (draftId == null || draftId.isBlank()) {
                throw new IllegalStateException("Juejin draft create returned no draft id");
            }
            JsonNode published = client.publishDraft(credential, draftId);
            String articleId = textValue(published.path("data"), "article_id");
            if (articleId == null || articleId.isBlank()) {
                articleId = textValue(published.path("data"), "id");
            }
            if (articleId == null || articleId.isBlank()) {
                throw new IllegalStateException("Juejin publish returned no article id");
            }
            return new BlogSyncResult("juejin", articleId,
                    "https://juejin.cn/post/" + articleId);
        } catch (PlatformJuejinClient.JuejinSessionException exception) {
            credentials.markExpired(request.userId());
            throw exception;
        }
    }

    private String resolveCategory(PublishRequest request) {
        String candidate = hasText(request.categoryId())
                ? request.categoryId() : request.categoryName();
        if (catalog.isPresent()) {
            catalog.get().ensureFresh(request.userId());
            return catalog.get().resolveCategoryId(candidate);
        }
        return candidate != null && NUMERIC_ID.matcher(candidate.strip()).matches()
                ? candidate.strip() : String.valueOf(PlatformJuejinCatalogService.DEFAULT_CATEGORY_ID);
    }

    private List<String> resolveTags(List<String> tags) {
        List<String> result = new ArrayList<>();
        if (tags != null) {
            for (String tag : tags) {
                if (!hasText(tag)) {
                    continue;
                }
                String value = tag.strip();
                String id = NUMERIC_ID.matcher(value).matches() ? value : resolveNamedTag(value);
                if (id != null && !id.isBlank() && !result.contains(id)) {
                    result.add(id);
                }
                if (result.size() == MAX_TAGS) {
                    break;
                }
            }
        }
        if (result.isEmpty()) {
            result.add(String.valueOf(PlatformJuejinCatalogService.DEFAULT_TAG_ID));
        }
        return List.copyOf(result);
    }

    private String resolveNamedTag(String name) {
        if (catalog.isPresent()) {
            String local = catalog.get().resolveTagId(name);
            if (local != null && !local.isBlank()) {
                return local;
            }
        }
        try {
            JsonNode data = client.searchTags(name, 8).path("data");
            if (!data.isArray()) {
                return null;
            }
            for (JsonNode item : data) {
                if (name.equalsIgnoreCase(item.path("tag_name").asText(""))) {
                    return tagId(item);
                }
            }
            return data.isEmpty() ? null : tagId(data.get(0));
        } catch (PlatformJuejinClient.JuejinApiException exception) {
            return null;
        }
    }

    static String buildBrief(String summary, String content) {
        String brief = hasText(summary) ? summary.strip() : firstParagraph(content);
        if (!hasText(brief)) {
            brief = hasText(content) ? content.strip() : "DinQorAI";
        }
        if (brief.length() > MAX_BRIEF_LENGTH) {
            return brief.substring(0, MAX_BRIEF_LENGTH);
        }
        if (brief.length() < MIN_BRIEF_LENGTH) {
            String seed = brief.isBlank() ? "DinQorAI" : brief;
            StringBuilder padded = new StringBuilder(brief);
            while (padded.length() < MIN_BRIEF_LENGTH) {
                int take = Math.min(MIN_BRIEF_LENGTH - padded.length(), seed.length());
                padded.append(seed, 0, take);
            }
            brief = padded.toString();
        }
        return brief;
    }

    static String firstParagraph(String content) {
        if (!hasText(content)) {
            return null;
        }
        for (String line : content.split("\\R")) {
            String cleaned = MARKDOWN_PREFIX.matcher(line.strip()).replaceFirst("").strip();
            if (cleaned.length() >= 10) {
                return cleaned;
            }
        }
        return null;
    }

    private static String tagId(JsonNode tag) {
        String id = tag.path("id").asText("");
        return id.isBlank() ? tag.path("tag_id").asText("") : id;
    }

    private String textValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private void validate(PublishRequest request) {
        if (request == null || request.userId() == null || request.userId() <= 0
                || request.postId() == null || request.postId() <= 0
                || !hasText(request.title()) || !hasText(request.content())) {
            throw new IllegalArgumentException("Juejin publication payload is incomplete");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record PublishRequest(Long userId, Long postId, String title, String summary,
                                 String content, String categoryId, String categoryName,
                                 String coverImage, List<String> tags) {
    }
}
