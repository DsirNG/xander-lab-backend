package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that user goals widen a fixed baseline tool surface without ever narrowing below it. */
class AgentSkillRegistryTest {

    @Test
    void writingRequestDoesNotExposePublishingMutation() {
        BuiltinAgentSkills builtins = new BuiltinAgentSkills();
        AgentToolRegistry tools = new AgentToolRegistry(List.of(
                tool("blog_generate"), tool("emit_artifact"), tool("publish_post"), tool("query_posts")));
        AgentSkillRegistry registry = new AgentSkillRegistry(List.of(
                builtins.generalChatSkill(), builtins.blogWritingSkill(), builtins.contentPublishingSkill(),
                builtins.artifactDeliverySkill()), tools);

        AgentSkillSelection selection = registry.resolve("帮我写一篇关于 RAG 的博客草稿");

        assertThat(selection.skillIds()).contains("blog-writing");
        assertThat(selection.toolNames()).contains("blog_generate").doesNotContain("publish_post");
        assertThat(selection.confidence()).isGreaterThan(0.5D);
    }

    @Test
    void ambiguousRequestStillWithholdsExternalMutation() {
        BuiltinAgentSkills builtins = new BuiltinAgentSkills();
        AgentToolRegistry tools = new AgentToolRegistry(List.of(
                tool("query_posts"), tool("publish_post"), tool("remember")));
        AgentSkillRegistry registry = new AgentSkillRegistry(List.of(
                builtins.generalChatSkill(), builtins.contentPublishingSkill()), tools);

        AgentSkillSelection selection = registry.resolve("帮我看看最近的情况");

        assertThat(selection.skillIds()).containsExactly("general-chat");
        // remember 属于基线：行动准则要求每轮该记的就记，那不能取决于用户有没有说"记住"。
        // publish_post 会推到站外，只有命中发布技能才进表。
        assertThat(selection.toolNames()).contains("query_posts", "remember").doesNotContain("publish_post");
    }

    /**
     * 回归："给我一个 sse 代码" 曾经只命中 artifact-delivery，当轮工具表就只剩 emit_artifact，
     * 模型除了开交付卡别无选择，而用户要的只是一段能扫一眼的片段。
     */
    @Test
    void codeQuestionKeepsMoreThanTheArtifactCard() {
        BuiltinAgentSkills builtins = new BuiltinAgentSkills();
        AgentToolRegistry tools = new AgentToolRegistry(List.of(
                tool("emit_artifact"), tool("query_knowledge"), tool("recall_memory"),
                tool("remember"), tool("plan_tasks"), tool("publish_post")));
        AgentSkillRegistry registry = new AgentSkillRegistry(List.of(
                builtins.generalChatSkill(), builtins.artifactDeliverySkill(),
                builtins.contentPublishingSkill()), tools);

        AgentSkillSelection selection = registry.resolve("给我一个sse代码");

        assertThat(selection.skillIds()).contains("artifact-delivery");
        assertThat(selection.toolNames())
                .contains("emit_artifact", "query_knowledge", "recall_memory", "remember", "plan_tasks")
                .doesNotContain("publish_post");
        assertThat(selection.instructions()).contains("代码围栏");
    }

    /** 基线是地板：技能只能往上加，不能把某个基线工具从表里拿掉。 */
    @Test
    void matchedSkillNeverNarrowsBelowBaseline() {
        BuiltinAgentSkills builtins = new BuiltinAgentSkills();
        List<AgentTool> registered = AgentSkillRegistry.BASELINE_TOOLS.stream()
                .map(this::tool).collect(Collectors.toList());
        registered.add(tool("publish_post"));
        AgentToolRegistry tools = new AgentToolRegistry(registered);
        AgentSkillRegistry registry = new AgentSkillRegistry(List.of(
                builtins.generalChatSkill(), builtins.contentPublishingSkill(),
                builtins.artifactDeliverySkill(), builtins.studyCoachSkill()), tools);

        for (String message : List.of("给我一个sse代码", "帮我复习一下", "把这篇同步发布出去", "今天天气怎么样")) {
            assertThat(registry.resolve(message).toolNames())
                    .as("baseline floor for: %s", message)
                    .containsAll(AgentSkillRegistry.BASELINE_TOOLS);
        }
    }

    /** 工具表参与提示词前缀，所以选出来的名字必须真的在注册表里，免得渲染出不存在的工具。 */
    @Test
    void unregisteredSkillToolsAreDroppedFromTheSelection() {
        BuiltinAgentSkills builtins = new BuiltinAgentSkills();
        AgentToolRegistry tools = new AgentToolRegistry(List.of(
                tool("remember"), tool("emit_artifact"), tool("query_knowledge")));
        AgentSkillRegistry registry = new AgentSkillRegistry(
                List.of(builtins.generalChatSkill(), builtins.artifactDeliverySkill()), tools);

        assertThat(registry.resolve("给我一个sse代码").toolNames())
                .containsExactlyInAnyOrder("emit_artifact", "query_knowledge", "remember");
    }

    private AgentTool tool(String name) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public JsonNode parametersSchema() {
                return JsonNodeFactory.instance.objectNode().put("type", "object");
            }
            @Override public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
                return JsonNodeFactory.instance.objectNode().put("ok", true);
            }
        };
    }
}
