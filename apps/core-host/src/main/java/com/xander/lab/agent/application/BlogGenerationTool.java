package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.blogagent.spi.BlogAgentPort;
import com.xander.lab.blogagent.spi.BlogAgentTaskDraft;
import com.xander.lab.blogagent.spi.BlogAgentTaskView;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * Exposes the full blog generation pipeline (analyze → research → write →
 * illustrate → review) as one agent tool. Progress stages are forwarded to
 * the conversation so the user can follow the long-running pipeline.
 *
 * <p>依赖 {@link BlogAgentPort} 而不是 {@code blogagent.application.BlogAgentService}
 * （跨部署单元：agent 在 {@code dinqor-agent}、blogagent 在 {@code dinqor-content-automation}）。
 * 本工具只读任务的 4 个字段，所以端口收的是 {@code BlogAgentTaskView} 而不是 25 个字段的域实体。</p>
 */
@Component
public class BlogGenerationTool implements AgentTool {

    private final BlogAgentPort blogAgentPort;

    public BlogGenerationTool(BlogAgentPort blogAgentPort) {
        this.blogAgentPort = blogAgentPort;
    }

    @Override
    public String name() {
        return "blog_generate";
    }

    @Override
    public String description() {
        return "把一个主题、日记片段或项目经历扩展成一篇可发布的中文知识博客。"
                + "工具会联网调研、撰写完整 Markdown 文章、按需生成知识插图并完成发布前审校。"
                + "用户明确要求写博客、写文章、沉淀知识笔记时使用本工具。"
                + "参数 input 为用户想要写的完整描述，必须原样包含用户所有关键信息。";
    }

    @Override
    public JsonNode parametersSchema() {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode schema = factory.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode input = properties.putObject("input");
        input.put("type", "string");
        input.put("description", "用户想写的主题、经历或项目描述的完整原文");
        schema.putArray("required").add("input");
        return schema;
    }

    @Override
    public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
        return execute(AgentToolContext.ofUser(userId), args, onProgress);
    }

    @Override
    public JsonNode execute(AgentToolContext context, JsonNode args, ProgressListener onProgress) {
        onProgress.checkCancellation();
        String input = args.path("input").asText("");
        if (!StringUtils.hasText(input)) {
            throw new IllegalArgumentException("博客生成工具缺少参数 input");
        }
        // 幂等键取本轮调用的 invocationId：重放同一个工具调用时不会建出第二个任务。
        BlogAgentTaskDraft draft = new BlogAgentTaskDraft(input, context.invocationId());
        BlogAgentTaskView task = blogAgentPort.create(context.userId(), draft);
        onProgress.onProgress("收到，开始处理你的主题…");
        // ⚠️ 端口是 (userId, taskId)，域侧是 (taskId, userId) —— 这里显式换位。
        BlogAgentTaskView result = blogAgentPort.runStream(context.userId(), task.id(), (event, data) -> {
                    if ("stage".equals(event)) {
                        onProgress.onProgress(prettyStage(String.valueOf(data)));
                    } else if ("delta".equals(event) && StringUtils.hasText(data)) {
                        onProgress.onToolDelta(data);
                    }
                }, onProgress::isCancellationRequested);
        onProgress.checkCancellation();

        return outcome(result);
    }

    @Override
    public Optional<JsonNode> reconcile(AgentToolContext context, JsonNode args) {
        if (!StringUtils.hasText(context.invocationId())) return Optional.empty();
        BlogAgentTaskView task =
                blogAgentPort.findReadyTaskByClientRequestId(context.userId(), context.invocationId());
        return task == null ? Optional.empty() : Optional.of(outcome(task));
    }

    private ObjectNode outcome(BlogAgentTaskView task) {
        ObjectNode outcome = JsonNodeFactory.instance.objectNode();
        outcome.put("ok", true);
        outcome.put("taskId", task.id());
        outcome.put("title", defaultText(task.title(), ""));
        outcome.put("summary", defaultText(task.summary(), ""));
        outcome.put("publishedPostId", task.publishedPostId() == null
                ? -1 : task.publishedPostId());
        outcome.put("hint", "文章已生成。你可以继续让用户在会话中修改，或提示用户查看任务 " + task.id() + " 进行发布。");
        return outcome;
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    /** 把流水线 stage 事件（stage|中文文案）转成用户友好的进度文案，保留 stage 供前端分段渲染。 */
    private String prettyStage(String raw) {
        int separator = raw.indexOf('|');
        if (separator < 0) {
            return raw;
        }
        String stage = raw.substring(0, separator);
        String message = raw.substring(separator + 1);
        String friendly = switch (stage) {
            case "analyze" -> "正在分析你的主题和文章定位…";
            case "research" -> "正在联网搜索相关资料…";
            case "write" -> "开始写作正文…";
            case "illustrate" -> "正在生成知识插图…";
            case "review" -> "正在做发布前审校…";
            default -> StringUtils.hasText(message) ? message : raw;
        };
        return stage + "|" + friendly;
    }
}
