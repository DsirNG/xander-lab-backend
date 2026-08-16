package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.identity.security.UserContext;
import com.xander.lab.publishing.api.BlogPostVO;
import com.xander.lab.publishing.application.BlogService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Lists the current user's blog posts that have NOT been successfully synced
 * to the given external platform (juejin / csdn). A single SQL query returns
 * all matching posts without pagination, so the agent never has to page
 * through results.
 */
@Component
public class QueryUnsyncedPostsTool implements AgentTool {

    private final BlogService blogService;

    public QueryUnsyncedPostsTool(BlogService blogService) {
        this.blogService = blogService;
    }

    @Override
    public String name() {
        return "query_unsynced_posts";
    }

    @Override
    public String description() {
        return "查询当前用户所有未成功同步到指定平台的博客文章（默认掘金 juejin，也可选 csdn），"
                + "一次调用返回全部未同步文章，无需分页。当用户问“哪些文章还没同步到掘金/CSDN”时使用本工具。"
                + "参数 platform 可选 juejin / csdn，默认 juejin；status 可选 draft / published / trashed，默认全部状态。";
    }

    @Override
    public JsonNode parametersSchema() {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode schema = factory.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode platform = properties.putObject("platform");
        platform.put("type", "string");
        platform.set("enum", JsonNodeFactory.instance.arrayNode().add("juejin").add("csdn"));
        platform.put("description", "目标平台，juejin（掘金）或 csdn，默认 juejin");
        ObjectNode status = properties.putObject("status");
        status.put("type", "string");
        status.set("enum", JsonNodeFactory.instance.arrayNode().add("draft").add("published").add("trashed"));
        status.put("description", "文章状态过滤，可选 draft / published / trashed，省略则返回全部状态");
        ObjectNode search = properties.putObject("search");
        search.put("type", "string");
        search.put("description", "标题或摘要关键词（可选）");
        return schema;
    }

    @Override
    public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
        onProgress.checkCancellation();
        String platform = normalizePlatform(args.path("platform").asText(""));
        Integer status = normalizeStatus(args.path("status").asText(""));
        String search = args.path("search").asText("");
        try {
            UserContext.setUserId(userId);
            boolean juejin = "juejin".equals(platform);
            List<BlogPostVO> unsynced = blogService.listMyPosts(status, search, juejin ? Boolean.FALSE : null, juejin ? null : Boolean.FALSE, null);
            ObjectNode outcome = JsonNodeFactory.instance.objectNode();
            outcome.put("ok", true);
            outcome.put("platform", platform);
            outcome.put("total", unsynced.size());
            ArrayNode posts = outcome.putArray("posts");
            for (BlogPostVO post : unsynced) {
                ObjectNode item = posts.addObject();
                item.put("id", post.getId());
                item.put("title", defaultText(post.getTitle(), ""));
                item.put("summary", defaultText(post.getSummary(), ""));
                item.put("status", statusText(post.getStatus()));
                item.put("date", post.getDate() == null ? "" : post.getDate().toString());
                item.put("category", defaultText(post.getCategoryName(), ""));
                item.put("url", "/blog/" + post.getId() + "/");
                ArrayNode tags = item.putArray("tags");
                if (post.getTags() != null) post.getTags().forEach(tags::add);
            }
            return outcome;
        } finally {
            UserContext.clear();
        }
    }

    private String normalizePlatform(String raw) {
        if (StringUtils.hasText(raw) && "csdn".equalsIgnoreCase(raw.trim())) {
            return "csdn";
        }
        return "juejin";
    }

    private Integer normalizeStatus(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        return switch (raw.trim().toLowerCase()) {
            case "draft" -> BlogService.STATUS_DRAFT;
            case "published", "publish" -> BlogService.STATUS_PUBLISHED;
            case "trashed", "trash", "deleted" -> BlogService.STATUS_TRASHED;
            default -> null;
        };
    }

    private String statusText(Integer status) {
        if (status == null) return "unknown";
        return switch (status) {
            case BlogService.STATUS_DRAFT -> "draft";
            case BlogService.STATUS_PUBLISHED -> "published";
            case BlogService.STATUS_TRASHED -> "trashed";
            default -> "unknown";
        };
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}