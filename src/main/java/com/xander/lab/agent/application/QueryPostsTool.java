package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.common.PageData;
import com.xander.lab.identity.security.UserContext;
import com.xander.lab.publishing.api.BlogPostVO;
import com.xander.lab.publishing.application.BlogService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Lists the current user's blog posts (draft / published / trashed) so the
 * agent can answer questions such as "我写过哪些文章" or pick an existing post
 * for revision or publication. Filters combine freely: status, keyword,
 * Juejin/CSDN sync state and an optional top-N limit. Scoped to the explicit
 * user id, never to a request thread context.
 */
@Component
public class QueryPostsTool implements AgentTool {

    private final BlogService blogService;

    public QueryPostsTool(BlogService blogService) {
        this.blogService = blogService;
    }

    @Override
    public String name() {
        return "query_posts";
    }

    @Override
    public String description() {
        return "查询当前用户自己的博客文章列表（草稿 / 已发布 / 回收站），筛选条件可自由组合："
                + "按状态（status）、标题摘要关键词（search）、同步状态（juejinSynced / csdnSynced 传 true 只看已同步、false 只看未同步）筛选。"
                + "传 limit 时一次返回最新前 N 篇（不分页）；不传 limit 时按 page / size 分页浏览。"
                + "每篇文章返回 juejinSynced / csdnSynced（是否已成功同步到对应平台）与 url（本站访问链接）。"
                + "当用户问“我写过哪些文章”、“有哪些草稿”、“前几篇文章”、“哪些文章已/未同步到掘金或CSDN”时使用本工具。";
    }

    @Override
    public JsonNode parametersSchema() {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode schema = factory.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode status = properties.putObject("status");
        status.put("type", "string");
        status.set("enum", JsonNodeFactory.instance.arrayNode().add("draft").add("published").add("trashed"));
        status.put("description", "文章状态过滤，可选 draft / published / trashed，省略则返回全部");
        ObjectNode search = properties.putObject("search");
        search.put("type", "string");
        search.put("description", "标题或摘要关键词（可选）");
        ObjectNode juejinSynced = properties.putObject("juejinSynced");
        juejinSynced.put("type", "boolean");
        juejinSynced.put("description", "true 仅返回已成功同步到掘金的文章，false 仅返回未同步的，省略则不限");
        ObjectNode csdnSynced = properties.putObject("csdnSynced");
        csdnSynced.put("type", "boolean");
        csdnSynced.put("description", "true 仅返回已成功同步到 CSDN 的文章，false 仅返回未同步的，省略则不限");
        ObjectNode limit = properties.putObject("limit");
        limit.put("type", "integer");
        limit.put("description", "返回最新前 N 篇（不分页，与 page/size 二选一）");
        ObjectNode page = properties.putObject("page");
        page.put("type", "integer");
        page.put("description", "页码，默认 1（仅在未传 limit 时生效）");
        ObjectNode size = properties.putObject("size");
        size.put("type", "integer");
        size.put("description", "每页数量，默认 10，最大 50（仅在未传 limit 时生效）");
        return schema;
    }

    @Override
    public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
        onProgress.checkCancellation();
        Integer status = normalizeStatus(args.path("status").asText(""));
        String search = args.path("search").asText("");
        Integer limit = args.path("limit").isNumber() ? Math.max(1, args.path("limit").asInt()) : null;
        Boolean juejinSynced = args.path("juejinSynced").isBoolean() ? args.path("juejinSynced").asBoolean() : null;
        Boolean csdnSynced = args.path("csdnSynced").isBoolean() ? args.path("csdnSynced").asBoolean() : null;
        try {
            UserContext.setUserId(userId);
            if (limit != null || juejinSynced != null || csdnSynced != null) {
                List<BlogPostVO> posts = blogService.listMyPosts(status, search, juejinSynced, csdnSynced, limit);
                return buildListOutcome(posts);
            }
            int page = Math.max(1, args.path("page").asInt(1));
            int size = Math.min(Math.max(1, args.path("size").asInt(10)), 50);
            PageData<BlogPostVO> result = blogService.getMyBlogs(status, search, page, size);
            ObjectNode outcome = JsonNodeFactory.instance.objectNode();
            outcome.put("ok", true);
            outcome.put("total", result.getTotal());
            outcome.put("page", result.getCurrent());
            outcome.put("size", result.getSize());
            outcome.put("hasMore", result.isHasMore());
            ArrayNode records = outcome.putArray("posts");
            for (BlogPostVO post : result.getRecords()) {
                appendPost(records, post);
            }
            return outcome;
        } finally {
            UserContext.clear();
        }
    }

    private ObjectNode buildListOutcome(List<BlogPostVO> posts) {
        ObjectNode outcome = JsonNodeFactory.instance.objectNode();
        outcome.put("ok", true);
        outcome.put("total", posts.size());
        ArrayNode records = outcome.putArray("posts");
        for (BlogPostVO post : posts) {
            appendPost(records, post);
        }
        return outcome;
    }

    private void appendPost(ArrayNode records, BlogPostVO post) {
        ObjectNode item = records.addObject();
        item.put("id", post.getId());
        item.put("title", defaultText(post.getTitle(), ""));
        item.put("summary", defaultText(post.getSummary(), ""));
        item.put("status", statusText(post.getStatus()));
        item.put("date", post.getDate() == null ? "" : post.getDate().toString());
        item.put("category", defaultText(post.getCategoryName(), ""));
        item.put("url", "/blog/" + post.getId() + "/");
        item.put("juejinSynced", Boolean.TRUE.equals(post.getJuejinSynced()));
        item.put("csdnSynced", Boolean.TRUE.equals(post.getCsdnSynced()));
        ArrayNode tags = item.putArray("tags");
        if (post.getTags() != null) post.getTags().forEach(tags::add);
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