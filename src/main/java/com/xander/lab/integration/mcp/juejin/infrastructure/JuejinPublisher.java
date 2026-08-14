package com.xander.lab.integration.mcp.juejin.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.xander.lab.integration.mcp.juejin.application.JuejinCredentialService;
import com.xander.lab.publishing.domain.BlogPublisher;
import com.xander.lab.publishing.domain.PublishCommand;
import com.xander.lab.publishing.domain.PublishResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Publishes an article through juejin.cn content_api: creates a draft, then publishes it.
 * The publish result is the juejin post URL; juejin requires a 50-100 character summary
 * ({@link #MIN_BRIEF_LENGTH}), so one is derived from the command when missing.
 */
@Component
@Slf4j
public class JuejinPublisher implements BlogPublisher {
    /** juejin 对摘要的硬性长度要求：50-100 字，低于 50 字直接返回参数错误。 */
    static final int MIN_BRIEF_LENGTH = 50;
    static final int MAX_BRIEF_LENGTH = 100;
    private static final Pattern MARKDOWN_PREFIX = Pattern.compile("^[#>*\\-+\\d.\\s]+");

    private final JuejinPlatformProperties properties;
    private final JuejinCredentialService credentials;
    private final JuejinClient client;

    public JuejinPublisher(JuejinPlatformProperties properties, JuejinCredentialService credentials, JuejinClient client) {
        this.properties = properties;
        this.credentials = credentials;
        this.client = client;
    }

    @Override public String platform() { return "juejin"; }

    @Override
    public PublishResult publish(PublishCommand command) {
        if (!properties.isEnabled()) throw new IllegalStateException("Juejin publisher is disabled");
        JuejinClient.Credential credential = credentials.requireCredential(command.userId());
        // juejin 的分类与标签使用掘金侧 ID（字符串），命令里没有时就落到前端分类。
        String categoryId = command.categoryId() == null || command.categoryId().isBlank()
                ? defaultCategoryId() : command.categoryId();
        String brief = buildBrief(command.summary(), command.content());
        try {
            JsonNode created = client.createDraft(credential, command.title(), brief, command.content(),
                    categoryId, command.tags(), command.coverImage());
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

    /** 掘金前端默认分类（前端）。 */
    private String defaultCategoryId() { return "6809637773935386632"; }

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
}