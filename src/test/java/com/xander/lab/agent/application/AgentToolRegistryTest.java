package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolRegistryTest {

    private final AgentToolRegistry registry = new AgentToolRegistry(List.of(
            tool("blog_generate"), tool("query_posts"), tool("query_unsynced_posts"),
            tool("publish_post"), tool("schedule_plan"), tool("query_plans"), tool("update_plan"),
            tool("email_reminder"), tool("query_email_reminders"), tool("update_email_reminder"),
            tool("image_generate")));

    @Test
    void plainConversationUsesNoBusinessToolSchemas() {
        assertThat(registry.selectNames("你好，解释一下什么是 SSE")).isEmpty();
        assertThat(registry.selectNames("给我200字文章")).isEmpty();
        assertThat(registry.describeTools(Set.of())).isEmpty();
    }

    @Test
    void explicitArticleWritingRequestSelectsGenerationTool() {
        assertThat(registry.selectNames("帮我写一篇200字的文章"))
                .containsExactly("blog_generate");
    }

    @Test
    void articleQueryDoesNotEnableGenerationTool() {
        assertThat(registry.selectNames("我有哪些文章"))
                .containsExactlyInAnyOrder("query_posts", "query_unsynced_posts");
    }

    @Test
    void selectsOnlyRelevantToolGroups() {
        Set<String> selected = registry.selectNames("给博客生成一张封面图，然后定时发布");

        assertThat(selected).contains(
                "blog_generate", "query_posts", "query_unsynced_posts",
                "image_generate", "schedule_plan", "query_plans", "update_plan",
                "publish_post");
        assertThat(selected).doesNotContain(
                "email_reminder", "query_email_reminders", "update_email_reminder");
    }

    @Test
    void selectsImageToolForTheReportedCatPrompt() {
        assertThat(registry.selectNames("生成图片：小猫"))
                .containsExactly("image_generate");
    }

    @Test
    void shortContextDependentActionFallsBackToAllTools() {
        assertThat(registry.selectNames("确认，按这个执行吧"))
                .containsExactlyInAnyOrderElementsOf(registry.names());
    }

    private AgentTool tool(String name) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name + " description";
            }

            @Override
            public JsonNode parametersSchema() {
                return JsonNodeFactory.instance.objectNode();
            }

            @Override
            public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
                return JsonNodeFactory.instance.objectNode();
            }
        };
    }
}
