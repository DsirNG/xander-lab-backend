package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.billing.application.BillingService;
import com.xander.lab.billing.domain.BillingBiz;
import com.xander.lab.blogagent.application.BlogAgentService;
import com.xander.lab.identity.security.UserContext;
import com.xander.lab.integration.mcp.csdn.application.CsdnBlogSyncService;
import com.xander.lab.integration.mcp.juejin.application.JuejinBlogSyncService;
import com.xander.lab.publishing.api.BlogPostVO;
import com.xander.lab.publishing.application.BlogService;
import com.xander.lab.publishing.domain.PublishResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * Publishes a blog post. Accepts either the id of a finished blog-generation
 * task (from {@code blog_generate}) or an existing local post id, and may
 * publish to the local site or synchronize to the owner's CSDN / Juejin
 * accounts. Local publishing first, then platform sync, keeps every attempt
 * durable: a platform failure leaves the local post intact for a safe retry.
 */
@Component
public class PublishPostTool implements AgentTool {

    private final BlogAgentService blogAgentService;
    private final BlogService blogService;
    private final CsdnBlogSyncService csdnBlogSyncService;
    private final JuejinBlogSyncService juejinBlogSyncService;
    private final BillingService billingService;

    public PublishPostTool(BlogAgentService blogAgentService, BlogService blogService,
                           CsdnBlogSyncService csdnBlogSyncService,
                           JuejinBlogSyncService juejinBlogSyncService,
                           BillingService billingService) {
        this.blogAgentService = blogAgentService;
        this.blogService = blogService;
        this.csdnBlogSyncService = csdnBlogSyncService;
        this.juejinBlogSyncService = juejinBlogSyncService;
        this.billingService = billingService;
    }

    @Override
    public String name() {
        return "publish_post";
    }

    @Override
    public String description() {
        return "把一篇已生成或已保存的文章发布出去。可发布到本站，也可同步到用户的 CSDN 或掘金账号。"
                + "参数 taskId 为博客生成工具返回的任务 id，postId 为已存在文章 id，二者任选其一；"
                + "platform 取值 local（本站，默认）、csdn、juejin。"
                + "用户明确要求发布、发表、投稿、同步到某平台时使用本工具。";
    }

    @Override
    public JsonNode parametersSchema() {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode schema = factory.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode taskId = properties.putObject("taskId");
        taskId.put("type", "integer");
        taskId.put("description", "博客生成任务 id（与 postId 二选一）");
        ObjectNode postId = properties.putObject("postId");
        postId.put("type", "integer");
        postId.put("description", "已存在的文章 id（与 taskId 二选一）");
        ObjectNode platform = properties.putObject("platform");
        platform.put("type", "string");
        platform.set("enum", JsonNodeFactory.instance.arrayNode().add("local").add("csdn").add("juejin"));
        platform.put("description", "发布目标平台，默认 local");
        return schema;
    }

    @Override
    public Optional<String> approvalReason(JsonNode args) {
        String platform = normalizePlatform(args.path("platform").asText("local"));
        return Optional.of("将文章发布到 " + platform + "，可能公开内容并触发平台操作");
    }

    @Override
    public Optional<JsonNode> reconcile(AgentToolContext context, JsonNode args) {
        long taskId = args.path("taskId").asLong(-1L);
        long postId = args.path("postId").asLong(-1L);
        String platform = normalizePlatform(args.path("platform").asText("local"));
        BlogPostVO local = taskId > 0
                ? blogAgentService.findPublishedPost(taskId, context.userId())
                : postId > 0 ? blogService.getManagedBlogById(context.userId(), postId) : null;
        if (local == null || local.getId() == null) return Optional.empty();

        ObjectNode outcome = JsonNodeFactory.instance.objectNode()
                .put("ok", true)
                .put("postId", local.getId())
                .put("title", defaultText(local.getTitle(), ""))
                .put("platform", platform);
        if ("csdn".equals(platform)) {
            PublishResult result = csdnBlogSyncService.findSucceeded(context.userId(), local.getId());
            if (result == null) return Optional.empty();
            fillPlatformResult(outcome, result);
        } else if ("juejin".equals(platform)) {
            PublishResult result = juejinBlogSyncService.findSucceeded(context.userId(), local.getId());
            if (result == null) return Optional.empty();
            fillPlatformResult(outcome, result);
        } else if (local.getStatus() == null || local.getStatus() != 1) {
            return Optional.empty();
        } else {
            outcome.put("hint", "文章已发布到本站");
        }
        return Optional.of(outcome);
    }

    @Override
    public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
        onProgress.checkCancellation();
        long taskId = args.path("taskId").asLong(-1L);
        long postId = args.path("postId").asLong(-1L);
        String platform = normalizePlatform(args.path("platform").asText("local"));
        if (taskId <= 0 && postId <= 0) {
            throw new IllegalArgumentException("发布工具需要提供 taskId 或 postId 中的至少一个");
        }
        onProgress.onProgress("正在准备发布文章…");

        // 智能体轮次在线程池线程上执行，请求线程的 UserContext 不会自动传递；
        // 本站发布链路（createBlog 等）依赖 UserContext 归属文章，需在此显式恢复并清理。
        try {
            UserContext.setUserId(userId);
            BlogPostVO local = resolveLocalPost(userId, taskId, postId);
            if (local == null || local.getId() == null) {
                throw new IllegalStateException("未找到可发布的文章，请先完成文章生成");
            }
            onProgress.checkCancellation();

            ObjectNode outcome = JsonNodeFactory.instance.objectNode();
            outcome.put("ok", true);
            outcome.put("postId", local.getId());
            outcome.put("title", defaultText(local.getTitle(), ""));
            outcome.put("platform", platform);
            if ("csdn".equals(platform)) {
                if (postId > 0 && Boolean.TRUE.equals(local.getCsdnSynced())) {
                    outcome.put("hint", "该文章已同步过 CSDN，未重复发布。");
                    return outcome;
                }
                onProgress.onProgress("正在同步到 CSDN…");
                PublishResult result = csdnBlogSyncService.sync(userId, local.getId());
                chargePlatformPublish(userId, BillingBiz.CSDN_PUBLISH, local.getId(), "CSDN 发布成功");
                fillPlatformResult(outcome, result);
            } else if ("juejin".equals(platform)) {
                if (postId > 0 && Boolean.TRUE.equals(local.getJuejinSynced())) {
                    outcome.put("hint", "该文章已同步过掘金，未重复发布。");
                    return outcome;
                }
                onProgress.onProgress("正在同步到掘金…");
                PublishResult result = juejinBlogSyncService.sync(userId, local.getId(), null, null);
                chargePlatformPublish(userId, BillingBiz.JUEJIN_PUBLISH, local.getId(), "掘金发布成功");
                fillPlatformResult(outcome, result);
            } else {
                outcome.put("hint", "文章已发布到本站。");
            }
            return outcome;
        } finally {
            UserContext.clear();
        }
    }

    private BlogPostVO resolveLocalPost(Long userId, long taskId, long postId) {
        if (taskId > 0) {
            return blogAgentService.publish(taskId, userId);
        }
        return blogService.getManagedBlogById(userId, postId);
    }

    private void fillPlatformResult(ObjectNode outcome, PublishResult result) {
        if (result == null) return;
        outcome.put("externalId", defaultText(result.externalId(), ""));
        outcome.put("url", defaultText(result.url(), ""));
        outcome.put("hint", "文章已同步到 " + defaultText(result.platform(), "") + "。");
    }

    private void chargePlatformPublish(Long userId, String bizType, Long postId, String remark) {
        billingService.chargeFlat(userId, bizType, bizType + ":" + postId,
                "post:" + postId, billingService.flatPoints(bizType), remark);
    }

    private String normalizePlatform(String value) {
        String platform = defaultText(value, "local").toLowerCase();
        return switch (platform) {
            case "csdn", "juejin" -> platform;
            default -> "local";
        };
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
