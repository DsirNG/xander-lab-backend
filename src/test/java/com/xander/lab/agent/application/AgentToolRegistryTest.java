package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
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

    @Test
    void theToolSurfaceCarriesThePoliciesOfTheToolsInTheTable() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(
                tool("emit_quiz", AgentPromptSection.delivery("出题必须下发答题卡")),
                tool("publish_post", AgentPromptSection.sideEffect("发布不可撤销")),
                tool("query_posts")));

        String surface = registry.describeToolSurface(Set.of("emit_quiz", "query_posts"));

        assertThat(surface)
                .contains("\"emit_quiz\"")
                .contains("工具使用准则")
                .contains("T1. 出题必须下发答题卡");
        // 不在本轮工具表里的工具不能在提示词里被规定行为：那是为模型根本调不到的工具立规矩，
        // 而"给我一个 sse 代码也被开成交付卡"正是这样来的。
        assertThat(surface).doesNotContain("发布不可撤销");
    }

    @Test
    void policiesAreNumberedByCategoryThenToolName() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(
                tool("publish_post", AgentPromptSection.sideEffect("站外发布不可撤销")),
                tool("grade_answer", AgentPromptSection.assessment("先判分再讲评")),
                tool("emit_quiz", AgentPromptSection.delivery("下发答题卡")),
                tool("emit_artifact", AgentPromptSection.delivery("默认写在代码围栏里"))));

        String surface = registry.describeToolSurface(new LinkedHashSet<>(
                List.of("publish_post", "grade_answer", "emit_quiz", "emit_artifact")));

        assertThat(surface)
                .contains("T1. 默认写在代码围栏里")
                .contains("T2. 下发答题卡")
                .contains("T3. 先判分再讲评")
                .contains("T4. 站外发布不可撤销");
        // 渲染只取决于工具集合本身，与集合的迭代顺序无关，提示词前缀才能进上游 prompt 缓存。
        assertThat(surface).isEqualTo(registry.describeToolSurface(new LinkedHashSet<>(
                List.of("emit_artifact", "emit_quiz", "grade_answer", "publish_post"))));
    }

    @Test
    void toolsWithoutAPolicyRenderSchemasOnly() {
        assertThat(registry.describeToolSurface(Set.of("plan_tasks")))
                .isEqualTo(registry.describeTools(Set.of("plan_tasks")))
                .doesNotContain("工具使用准则");
        assertThat(registry.describeToolSurface(Set.of())).isEmpty();
    }

    private AgentTool tool(String name) {
        return tool(name, null);
    }

    private AgentTool tool(String name, AgentPromptSection section) {
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
            public Optional<AgentPromptSection> promptSection() {
                return Optional.ofNullable(section);
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
