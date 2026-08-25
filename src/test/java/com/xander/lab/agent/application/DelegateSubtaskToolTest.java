package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DelegateSubtaskToolTest {

    private final SubAgentRunner runner = mock(SubAgentRunner.class);
    private final DelegateSubtaskTool tool = new DelegateSubtaskTool(runner);
    private final AgentToolContext context = new AgentToolContext(42L, 7L, 3L);

    @Test
    void handsTheSubtaskToTheRunnerAndReportsBackWhatItConcluded() {
        when(runner.run(eq(context), eq("盘点三角函数笔记"), eq("用户在准备期末复习"),
                eq(Set.of("query_knowledge")), any()))
                .thenReturn(new SubAgentRunner.SubAgentResult("盘点三角函数笔记", "共 3 条笔记，其中 1 条待复习",
                        4, List.of("query_knowledge", "query_knowledge"), true, List.of("query_knowledge")));

        JsonNode result = tool.execute(context, args("盘点三角函数笔记", "用户在准备期末复习", "query_knowledge"),
                new NoopProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("completed").asBoolean()).isTrue();
        assertThat(result.path("objective").asText()).isEqualTo("盘点三角函数笔记");
        assertThat(result.path("steps").asInt()).isEqualTo(4);
        assertThat(result.path("toolCalls").toString()).isEqualTo("[\"query_knowledge\",\"query_knowledge\"]");
        assertThat(result.path("findings").asText()).isEqualTo("共 3 条笔记，其中 1 条待复习");
        assertThat(result.path("hint").asText()).contains("不是你的答复");
    }

    @Test
    void anUnfinishedSubtaskIsLabelledAsMidFlightObservationsRatherThanAConclusion() {
        when(runner.run(any(), any(), any(), any(), any()))
                .thenReturn(new SubAgentRunner.SubAgentResult("盘点笔记", "只查到一半就没步数了",
                        6, List.of("query_knowledge"), false, List.of("query_knowledge")));

        JsonNode result = tool.execute(context, args("盘点笔记", "", null), new NoopProgress());

        assertThat(result.path("completed").asBoolean()).isFalse();
        assertThat(result.path("hint").asText()).contains("中途观察").contains("如实说明");
    }

    @Test
    void passesNoWhitelistWhenTheAgentDidNotAskForOneSoTheSubAgentPicksItsOwnTools() {
        when(runner.run(any(), any(), any(), any(), any()))
                .thenReturn(new SubAgentRunner.SubAgentResult("查笔记", "查到了", 1, List.of(), true, List.of()));

        tool.execute(context, args("查笔记", "背景", null), new NoopProgress());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> tools = ArgumentCaptor.forClass(Set.class);
        verify(runner).run(eq(context), eq("查笔记"), eq("背景"), tools.capture(), any());
        assertThat(tools.getValue()).isEmpty();
    }

    @Test
    void ignoresGarbageInTheToolWhitelistInsteadOfFailingTheCall() {
        when(runner.run(any(), any(), any(), any(), any()))
                .thenReturn(new SubAgentRunner.SubAgentResult("查笔记", "查到了", 1, List.of(), true, List.of()));
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("objective", "查笔记");
        args.putArray("tools").add(" query_knowledge ").add("").addObject().put("tool", "nope");

        tool.execute(context, args, new NoopProgress());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> tools = ArgumentCaptor.forClass(Set.class);
        verify(runner).run(any(), any(), any(), tools.capture(), any());
        // 空串丢掉、首尾空格修掉；对象元素被 asText 渲染成空串所以同样丢掉。
        assertThat(tools.getValue()).containsExactly("query_knowledge");
    }

    @Test
    void refusesToRunWithoutAConversationBecauseTheSubAgentInheritsTheSessionContext() {
        assertThatThrownBy(() -> tool.execute(42L, args("查笔记", "", null), new NoopProgress()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须在会话上下文中执行");
        assertThatThrownBy(() -> tool.execute(AgentToolContext.ofUser(42L), args("查笔记", "", null),
                new NoopProgress()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须在会话上下文中执行");
    }

    @Test
    void schemaOnlyRequiresAnObjectiveAndTheDescriptionTellsTheAgentWhenDelegatingPaysOff() {
        JsonNode schema = tool.parametersSchema();

        assertThat(schema.path("required").toString()).isEqualTo("[\"objective\"]");
        assertThat(schema.path("properties").path("brief").path("type").asText()).isEqualTo("string");
        assertThat(schema.path("properties").path("tools").path("type").asText()).isEqualTo("array");
        assertThat(schema.path("properties").path("tools").path("items").path("type").asText())
                .isEqualTo("string");
        assertThat(tool.name()).isEqualTo(DelegateSubtaskTool.NAME);
        assertThat(tool.description()).contains("上下文").contains("brief 必须自包含");
    }

    private ObjectNode args(String objective, String brief, String toolName) {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("objective", objective);
        args.put("brief", brief);
        if (toolName != null) args.putArray("tools").add(toolName);
        return args;
    }

    private static final class NoopProgress implements AgentTool.ProgressListener {
        @Override
        public void onProgress(String message) {
        }
    }
}
