package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.payment.api.BillingBiz;
import com.xander.lab.payment.api.BillingPort;
import com.xander.lab.blogagent.spi.BlogAgentPort;
import com.xander.lab.blogagent.spi.BlogAgentPostView;
import com.xander.lab.auth.core.UserContext;
import com.xander.lab.content.api.ContentPost;
import com.xander.lab.content.api.ContentPostPort;
import com.xander.lab.integration.mcp.csdn.application.CsdnBlogSyncService;
import com.xander.lab.integration.mcp.juejin.application.JuejinBlogSyncService;
import com.xander.lab.content.api.PublishResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * Publishes a blog post. Accepts either the id of a finished blog-generation
 * task (from {@code blog_generate}) or an existing local post id, and may
 * publish to the local site or synchronize to the owner's CSDN / Juejin
 * accounts. Local publishing first, then platform sync, keeps every attempt
 * durable: a platform failure leaves the local post intact for a safe retry.
 *
 * <p>读本地文章有两条来源，都跨部署单元（agent 在 {@code dinqor-agent}、内容域与 blogagent
 * 在 {@code dinqor-content-automation}）：</p>
 * <ul>
 *   <li>{@code taskId} 走 {@link BlogAgentPort#publish} / {@link BlogAgentPort#findPublishedPost}
 *       —— blogagent 的生成任务。</li>
 *   <li>{@code postId} 走 {@link ContentPostPort} —— 已存在的本地文章。</li>
 * </ul>
 * <p>两条来源在本类里归一成 {@link LocalPost}。本工具只读 5 个字段，所以两个端口各自只带那 5 个 ——
 * <b>不为了统一返回类型把整个 {@code BlogPostVO}（17 个字段）搬上端口</b>。
 * 归一放在工具内部而不是端口上，是因为 {@code LocalPost} 是**本工具的概念**
 * （"一条本地文章，来源不限"），把它塞进 blogagent 的契约等于让 blogagent 替内容域发言。</p>
 */
@Component
public class PublishPostTool implements AgentTool {

    private final BlogAgentPort blogAgentPort;
    private final ContentPostPort contentPostPort;
    private final CsdnBlogSyncService csdnBlogSyncService;
    private final JuejinBlogSyncService juejinBlogSyncService;
    private final BillingPort billingPort;

    public PublishPostTool(BlogAgentPort blogAgentPort, ContentPostPort contentPostPort,
                           CsdnBlogSyncService csdnBlogSyncService,
                           JuejinBlogSyncService juejinBlogSyncService,
                           BillingPort billingPort) {
        this.blogAgentPort = blogAgentPort;
        this.contentPostPort = contentPostPort;
        this.csdnBlogSyncService = csdnBlogSyncService;
        this.juejinBlogSyncService = juejinBlogSyncService;
        this.billingPort = billingPort;
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
    public Optional<AgentPromptSection> promptSection() {
        return Optional.of(AgentPromptSection.sideEffect(
                "publish_post 会把内容推到站外，是这一轮里唯一不可撤销的动作。"
                        + "调用前必须先确认具体哪一篇和哪个平台，不要用推测的 taskId/postId 试；"
                        + "生成草稿不等于发布，用户没明确说要发就不要发。"
                        + "发布失败时如实说明失败平台和原因，绝不把失败说成已发布。"));
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
        LocalPost local = taskId > 0
                // ⚠️ 端口是 (userId, taskId)，域侧是 (taskId, userId) —— 显式换位。
                ? fromGenerated(blogAgentPort.findPublishedPost(context.userId(), taskId))
                : postId > 0 ? fromContract(contentPostPort.findOwned(context.userId(), postId)) : null;
        if (local == null || local.id() == null) return Optional.empty();

        ObjectNode outcome = JsonNodeFactory.instance.objectNode()
                .put("ok", true)
                .put("postId", local.id())
                .put("title", defaultText(local.title(), ""))
                .put("platform", platform);
        if ("csdn".equals(platform)) {
            PublishResult result = csdnBlogSyncService.findSucceeded(context.userId(), local.id());
            if (result == null) return Optional.empty();
            fillPlatformResult(outcome, result);
        } else if ("juejin".equals(platform)) {
            PublishResult result = juejinBlogSyncService.findSucceeded(context.userId(), local.id());
            if (result == null) return Optional.empty();
            fillPlatformResult(outcome, result);
        } else if (local.status() == null || local.status() != 1) {
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
        // 本站发布链路（PublicationService -> LocalBlogPublisher -> BlogService.createBlog）
        // 仍然读 UserContext 来归属文章，需在此显式恢复并清理。
        try {
            UserContext.setUserId(userId);
            LocalPost local = resolveLocalPost(userId, taskId, postId);
            if (local == null || local.id() == null) {
                throw new IllegalStateException("未找到可发布的文章，请先完成文章生成");
            }
            onProgress.checkCancellation();

            ObjectNode outcome = JsonNodeFactory.instance.objectNode();
            outcome.put("ok", true);
            outcome.put("postId", local.id());
            outcome.put("title", defaultText(local.title(), ""));
            outcome.put("platform", platform);
            if ("csdn".equals(platform)) {
                if (postId > 0 && Boolean.TRUE.equals(local.csdnSynced())) {
                    outcome.put("hint", "该文章已同步过 CSDN，未重复发布。");
                    return outcome;
                }
                onProgress.onProgress("正在同步到 CSDN…");
                PublishResult result = csdnBlogSyncService.sync(userId, local.id());
                chargePlatformPublish(userId, BillingBiz.CSDN_PUBLISH, local.id(), "CSDN 发布成功");
                fillPlatformResult(outcome, result);
            } else if ("juejin".equals(platform)) {
                if (postId > 0 && Boolean.TRUE.equals(local.juejinSynced())) {
                    outcome.put("hint", "该文章已同步过掘金，未重复发布。");
                    return outcome;
                }
                onProgress.onProgress("正在同步到掘金…");
                PublishResult result = juejinBlogSyncService.sync(userId, local.id(), null, null);
                chargePlatformPublish(userId, BillingBiz.JUEJIN_PUBLISH, local.id(), "掘金发布成功");
                fillPlatformResult(outcome, result);
            } else {
                outcome.put("hint", "文章已发布到本站。");
            }
            return outcome;
        } finally {
            UserContext.clear();
        }
    }

    private LocalPost resolveLocalPost(Long userId, long taskId, long postId) {
        if (taskId > 0) {
            // ⚠️ 端口是 (userId, taskId)，域侧是 (taskId, userId) —— 显式换位。
            return fromGenerated(blogAgentPort.publish(userId, taskId));
        }
        return fromContract(contentPostPort.findOwned(userId, postId));
    }

    /**
     * 本地文章的两种来源归一成的形状。
     *
     * <p>一条来自 {@link BlogAgentPort}（返回契约类型 {@link BlogAgentPostView}），一条来自
     * {@link ContentPostPort}（返回契约类型 {@link ContentPost}）。本工具只读这 5 个字段，
     * 所以两个端口各自只带这 5 个 —— <b>不为了统一返回类型把整个 {@code BlogPostVO}
     * （17 个字段）搬上端口</b>。端口只收调用方真正用到的字段，这也是断环的手段。</p>
     *
     * <p>两个契约类型形状相同但**不能合并**：契约不许依赖契约（只允许 JDK + 本包），
     * 而且 {@link BlogAgentPostView} 是 blogagent 的能力面、{@link ContentPost} 是内容域的文章模型，
     * 让其中一个替另一个发言就是把两个域的词汇表绑在一起。归一放在本工具内部，代价是这 5 行。</p>
     */
    private record LocalPost(Long id, String title, Integer status, Boolean csdnSynced, Boolean juejinSynced) {
    }

    /** blogagent 端口的发布结果 → 本工具用的形状。 */
    private static LocalPost fromGenerated(BlogAgentPostView post) {
        return post == null ? null : new LocalPost(post.id(), post.title(), post.status(),
                post.csdnSynced(), post.juejinSynced());
    }

    /** 内容域端口 → 本工具用的形状。 */
    private static LocalPost fromContract(ContentPost post) {
        return post == null ? null : new LocalPost(post.id(), post.title(), post.status(),
                post.csdnSynced(), post.juejinSynced());
    }

    private void fillPlatformResult(ObjectNode outcome, PublishResult result) {
        if (result == null) return;
        outcome.put("externalId", defaultText(result.externalId(), ""));
        outcome.put("url", defaultText(result.url(), ""));
        outcome.put("hint", "文章已同步到 " + defaultText(result.platform(), "") + "。");
    }

    private void chargePlatformPublish(Long userId, String bizType, Long postId, String remark) {
        billingPort.chargeFlat(userId, bizType, bizType + ":" + postId,
                "post:" + postId, billingPort.flatPoints(bizType), remark);
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
