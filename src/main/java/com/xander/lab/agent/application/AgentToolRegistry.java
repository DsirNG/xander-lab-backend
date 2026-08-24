package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Registers the tools an agent conversation may invoke, keyed by name. */
@Component
public class AgentToolRegistry {

    private static final Set<String> PUBLISH_TOOLS = Set.of(
            "publish_post", "query_posts", "query_unsynced_posts");
    private static final Set<String> PLAN_TOOLS = Set.of(
            "schedule_plan", "query_plans", "update_plan");
    private static final Set<String> EMAIL_TOOLS = Set.of(
            "email_reminder", "query_email_reminders", "update_email_reminder");
    private static final Set<String> IMAGE_TOOLS = Set.of("image_generate");

    private static final List<String> CONTENT_CREATION_MARKERS = List.of(
            "写博客", "写博文", "写文章", "写一篇",
            "撰写博客", "撰写博文", "撰写文章",
            "生成博客", "生成博文", "生成文章", "博客生成", "博文生成", "文章生成",
            "创作博客", "创作博文", "创作文章",
            "改写博客", "改写博文", "改写文章",
            "润色博客", "润色博文", "润色文章",
            "write a blog", "write a post", "write an article",
            "generate a blog", "generate a post", "generate an article");
    private static final List<String> CONTENT_QUERY_MARKERS = List.of(
            "哪些博客", "哪些博文", "哪些文章", "博客列表", "博文列表", "文章列表",
            "我的博客", "我的博文", "我的文章", "我写过");
    private static final List<String> PUBLISH_MARKERS = List.of(
            "发布", "发文", "同步", "草稿", "csdn", "掘金", "公众号", "publish", "sync");
    private static final List<String> PLAN_MARKERS = List.of(
            "定时", "计划", "排期", "调度", "日程", "schedule", "plan");
    private static final List<String> EMAIL_MARKERS = List.of(
            "邮件", "邮箱", "提醒", "email", "mail", "reminder");
    private static final List<String> IMAGE_MARKERS = List.of(
            "图片", "配图", "插图", "封面", "图像", "image", "illustration", "cover");
    private static final List<String> CONTEXT_DEPENDENT_ACTION_MARKERS = List.of(
            "继续", "执行", "确认", "开始吧", "就这样", "按这个", "照这个", "取消", "触发",
            "continue", "execute", "confirm", "go ahead", "do it", "cancel");

    private final Map<String, AgentTool> tools;

    public AgentToolRegistry(List<AgentTool> availableTools) {
        this.tools = availableTools.stream()
                .collect(Collectors.toUnmodifiableMap(AgentTool::name, Function.identity()));
    }

    public AgentTool get(String name) {
        return tools.get(name);
    }

    public List<String> names() {
        return List.copyOf(tools.keySet());
    }

    /**
     * Selects the smallest safe business-tool set from the current user turn.
     * Plain conversation receives no schemas; context-dependent action phrases
     * deliberately fall back to every tool so a short follow-up never loses capability.
     */
    public Set<String> selectNames(String userMessage) {
        String normalized = userMessage == null ? "" : userMessage.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        addWhenMatched(selected, normalized, CONTENT_CREATION_MARKERS, Set.of("blog_generate"));
        addWhenMatched(selected, normalized, CONTENT_QUERY_MARKERS,
                Set.of("query_posts", "query_unsynced_posts"));
        addWhenMatched(selected, normalized, PUBLISH_MARKERS, PUBLISH_TOOLS);
        addWhenMatched(selected, normalized, PLAN_MARKERS, PLAN_TOOLS);
        addWhenMatched(selected, normalized, EMAIL_MARKERS, EMAIL_TOOLS);
        addWhenMatched(selected, normalized, IMAGE_MARKERS, IMAGE_TOOLS);
        if (selected.isEmpty() && containsAny(normalized, CONTEXT_DEPENDENT_ACTION_MARKERS)) {
            selected.addAll(tools.keySet());
        }
        selected.retainAll(tools.keySet());
        return Set.copyOf(selected);
    }

    /** Renders name/description/schema so the model can choose a tool. */
    public String describeTools() {
        return describeTools(tools.keySet());
    }

    /** Renders only the selected tool schemas; an empty selection enables the fast chat path. */
    public String describeTools(Set<String> selectedNames) {
        if (selectedNames == null || selectedNames.isEmpty()) return "";
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode root = factory.objectNode();
        ObjectNode toolsNode = root.putObject("tools");
        tools.forEach((name, tool) -> {
            if (!selectedNames.contains(name)) return;
            ObjectNode entry = toolsNode.putObject(name);
            entry.put("description", tool.description());
            entry.set("parameters", tool.parametersSchema());
        });
        return root.toString();
    }

    private void addWhenMatched(Set<String> selected, String message,
                                List<String> markers, Set<String> names) {
        if (containsAny(message, markers)) selected.addAll(names);
    }

    private boolean containsAny(String message, List<String> markers) {
        return markers.stream().anyMatch(message::contains);
    }
}
