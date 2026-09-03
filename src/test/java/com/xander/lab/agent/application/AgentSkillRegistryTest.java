package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that user goals activate bounded skill tool surfaces. */
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
    void ambiguousRequestFallsBackToReadOnlyTools() {
        BuiltinAgentSkills builtins = new BuiltinAgentSkills();
        AgentToolRegistry tools = new AgentToolRegistry(List.of(
                tool("query_posts"), tool("publish_post"), tool("remember")));
        AgentSkillRegistry registry = new AgentSkillRegistry(List.of(
                builtins.generalChatSkill(), builtins.contentPublishingSkill()), tools);

        AgentSkillSelection selection = registry.resolve("帮我看看最近的情况");

        assertThat(selection.skillIds()).containsExactly("general-chat");
        assertThat(selection.toolNames()).contains("query_posts").doesNotContain("publish_post", "remember");
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
