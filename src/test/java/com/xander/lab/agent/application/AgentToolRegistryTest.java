package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolRegistryTest {

    private final AgentToolRegistry registry = new AgentToolRegistry(List.of(
            tool("query_posts"), tool("blog_generate"), tool("query_unsynced_posts"),
            tool("publish_post"), tool("schedule_plan"), tool("query_plans"), tool("update_plan"),
            tool("email_reminder"), tool("query_email_reminders"), tool("update_email_reminder"),
            tool("image_generate"), tool("create_knowledge"), tool("query_knowledge"),
            tool("plan_tasks"), tool("remember"), tool("recall_memory")));

    @Test
    void everyTurnSeesTheWholeToolTable() {
        // 关键词门禁已移除：能力边界不再由规则层代模型决定，否则匹配不上词表的诉求会静默失能。
        assertThat(registry.allowedNames()).containsExactlyInAnyOrderElementsOf(registry.names());
        assertThat(registry.allowedNames()).contains("plan_tasks", "remember", "recall_memory");
    }

    @Test
    void toolNamesAreRenderedInStableOrder() {
        // 顺序稳定，同一套工具每轮渲染出的前缀完全一致，便于上游做 prompt 缓存。
        assertThat(registry.names()).isSorted();
        assertThat(registry.describeTools()).isEqualTo(registry.describeTools());
        assertThat(registry.describeTools().indexOf("\"blog_generate\""))
                .isLessThan(registry.describeTools().indexOf("\"query_posts\""));
    }

    @Test
    void describedToolsCarryDescriptionAndSchema() {
        String described = registry.describeTools(Set.of("plan_tasks"));

        assertThat(described).contains("plan_tasks").contains("plan_tasks description");
        assertThat(described).doesNotContain("query_posts");
    }

    @Test
    void emptySelectionRendersNoSchemaBlock() {
        assertThat(registry.describeTools(Set.of())).isEmpty();
        assertThat(registry.describeTools(null)).isEmpty();
    }

    @Test
    void unknownToolNameResolvesToNull() {
        assertThat(registry.get("no_such_tool")).isNull();
        assertThat(registry.get("plan_tasks")).isNotNull();
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
