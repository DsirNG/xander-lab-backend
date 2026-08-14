package com.xander.lab.integration.mcp.juejin.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.xander.lab.integration.mcp.juejin.application.JuejinCatalogService;
import com.xander.lab.integration.mcp.juejin.application.JuejinCredentialService;
import com.xander.lab.publishing.domain.BlogPublisher;
import com.xander.lab.publishing.domain.PublishCommand;
import com.xander.lab.publishing.domain.PublishResult;
import com.xander.lab.publishing.domain.PublishingLimits;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Publishes an article through juejin.cn content_api: creates a draft, then publishes it.
 * The publish result is the juejin post URL; juejin requires a 50-100 character summary
 * ({@link #MIN_BRIEF_LENGTH}) and at least one Juejin-side tag id, so the summary is derived
 * from the command when missing and tag names are resolved to tag ids via the synced
 * category/tag catalog (with a remote search fallback).
 */
@Component
@Slf4j
public class JuejinPublisher implements BlogPublisher {
    /** juejin 对摘要的硬性长度要求：50-100 字，低于 50 字直接返回参数错误。 */
    static final int MIN_BRIEF_LENGTH = 50;
    static final int MAX_BRIEF_LENGTH = PublishingLimits.MAX_SUMMARY_LENGTH;
    /** 掘金编辑器最多允许 3 个标签，解析与透传都按此封顶。 */
    static final int MAX_TAGS = PublishingLimits.MAX_JUEJIN_TAGS;
    private static final Pattern MARKDOWN_PREFIX = Pattern.compile("^[#>*\\-+\\d.\\s]+");
    private static final Pattern NUMERIC_TAG_ID = Pattern.compile("\\d+");

    private final JuejinPlatformProperties properties;
    private final JuejinCredentialService credentials;
    private final JuejinClient client;
    private final JuejinCatalogService catalogService;

    public JuejinPublisher(JuejinPlatformProperties properties, JuejinCredentialService credentials,
                           JuejinClient client, JuejinCatalogService catalogService) {
        this.properties = properties;
        this.credentials = credentials;
        this.client = client;
        this.catalogService = catalogService;
    }

    @Override public String platform() { return "juejin"; }

    @Override
    public PublishResult publish(PublishCommand command) {
        if (!properties.isEnabled()) throw new IllegalStateException("Juejin publisher is disabled");
        JuejinClient.Credential credential = credentials.requireCredential(command.userId());
        // 目录过期或为空时先同步（失败只降级不阻断发布）；分类名/ID 统一交给目录解析，
        // 解析不了时回落默认前端分类。
        catalogService.ensureFresh(credential, command.userId());
        String categoryId = catalogService.resolveCategoryId(command.categoryId());
        String brief = buildBrief(command.summary(), command.content());
        try {
            JsonNode created = client.createDraft(credential, command.title(), brief, command.content(),
                    categoryId, resolveTagIds(command.tags()), command.coverImage());
            String draftId = textValue(created.path("data"), "id");
            if (draftId == null || draftId.isBlank()) {
                throw new IllegalStateException("Juejin draft create returned no draft id");
            }
            JsonNode published = client.publishDraft(credential, draftId);
            String articleId = textValue(published.path("data"), "article_id");
            if (articleId == null || articleId.isBlank()) articleId = textValue(published.path("data"), "id");
            if (articleId == null || articleId.isBlank()) {
                throw new IllegalStateException("Juejin publish returned no article id");
            }
            return new PublishResult(platform(), articleId, "https://juejin.cn/post/" + articleId);
        } catch (JuejinClient.JuejinSessionException ex) {
            credentials.markExpired(command.userId());
            log.warn("[Juejin] 掘金登录态已失效（{}），用户 {} 的授权被标记为待重新授权", ex.getMessage(), command.userId());
            throw ex;
        }
    }

    private String textValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    /**
     * 摘要取命令中的摘要，缺失时从正文第一个非标题段落提取；再补齐到 50-100 字。
     * 掘金硬性要求 50-100 字，过短会被判参数错误。
     */
    static String buildBrief(String summary, String markContent) {
        String brief = summary;
        if (brief == null || brief.isBlank()) {
            brief = firstParagraph(markContent);
        }
        if (brief == null) brief = "";
        brief = brief.strip();
        if (brief.length() > MAX_BRIEF_LENGTH) {
            return brief.substring(0, MAX_BRIEF_LENGTH);
        }
        if (brief.length() < MIN_BRIEF_LENGTH) {
            // 过短时按前缀重复补齐（与掘金自动摘要行为一致），避免 API 参数错误。
            StringBuilder padded = new StringBuilder(brief);
            while (padded.length() < MIN_BRIEF_LENGTH) {
                int take = Math.min(MIN_BRIEF_LENGTH - padded.length(), brief.length());
                padded.append(brief, 0, Math.max(1, take));
            }
            brief = padded.toString();
        }
        return brief;
    }

    /** 返回第一个去掉 Markdown 标记后非空且有内容的段落。 */
    static String firstParagraph(String markContent) {
        if (markContent == null || markContent.isBlank()) return null;
        for (String line : markContent.split("\n")) {
            String cleaned = MARKDOWN_PREFIX.matcher(line.strip()).replaceFirst("").strip();
            if (cleaned.length() >= 10) return cleaned;
        }
        return null;
    }

    /**
     * 把发布命令里的标签统一转换为掘金 tag_id：纯数字当作 ID 直接透传，
     * 其余先查本地目录（名称→ID 匹配），目录未命中再调用掘金标签搜索兜底，
     * 最多取 {@link #MAX_TAGS} 个。一个都解析不出来时使用默认标签，
     * 避免掘金「至少添加一个标签」的模糊报错。
     */
    List<String> resolveTagIds(List<String> tags) {
        List<String> ids = new ArrayList<>();
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) continue;
            String trimmed = tag.strip();
            if (NUMERIC_TAG_ID.matcher(trimmed).matches()) {
                ids.add(trimmed);
            } else {
                // 目录精确匹配优先于远程搜索：命中即返回，未命中再走搜索兜底。
                String catalogId = catalogService.resolveTagId(trimmed);
                if (catalogId != null) {
                    ids.add(catalogId);
                } else {
                    String resolved = resolveTagId(trimmed);
                    if (resolved != null) ids.add(resolved);
                }
            }
            if (ids.size() >= MAX_TAGS) break;
        }
        if (ids.isEmpty()) {
            ids.add(JuejinCatalogService.DEFAULT_TAG_ID);
        }
        return List.copyOf(ids);
    }

    /** 按名称搜索掘金标签：优先取名称精确匹配项，否则取搜索结果第一条（按相关性排序）。 */
    private String resolveTagId(String name) {
        JsonNode data;
        try {
            data = client.searchTags(name, 8).path("data");
        } catch (JuejinClient.JuejinApiException ex) {
            log.warn("[Juejin] 标签「{}」查询失败（{}），已跳过该标签", name, ex.getMessage());
            return null;
        }
        if (!data.isArray() || data.isEmpty()) return null;
        for (JsonNode tag : data) {
            if (name.equalsIgnoreCase(tag.path("tag_name").asText(""))) {
                return tagId(tag);
            }
        }
        return tagId(data.get(0));
    }

    private static String tagId(JsonNode tag) {
        String id = tag.path("id").asText("");
        return id.isBlank() ? tag.path("tag_id").asText("") : id;
    }
}