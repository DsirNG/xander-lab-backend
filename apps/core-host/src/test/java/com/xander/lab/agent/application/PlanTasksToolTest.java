package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.agent.domain.AgentPlanItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanTasksToolTest {

    private final AgentPlanService planService = mock(AgentPlanService.class);
    private final PlanTasksTool tool = new PlanTasksTool(planService);

    @Test
    void savesThePlanAndTellsTheAgentToKeepWorking() {
        List<AgentPlanItem> saved = List.of(
                new AgentPlanItem("查一下知识库", "DONE", ""),
                new AgentPlanItem("整理复习清单", "IN_PROGRESS", "按掌握度排序"));
        when(planService.replace(eq(7L), eq(42L), eq(3L), any())).thenReturn(saved);
        when(planService.unfinished(saved)).thenReturn(List.of(saved.get(1)));

        JsonNode result = tool.execute(new AgentToolContext(42L, 7L, 3L), args(), new NoopProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("total").asInt()).isEqualTo(2);
        assertThat(result.path("unfinished").asInt()).isEqualTo(1);
        assertThat(result.path("items").get(1).path("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(result.path("items").get(1).path("note").asText()).isEqualTo("按掌握度排序");
        assertThat(result.path("items").get(0).has("note")).isFalse();
        assertThat(result.path("hint").asText()).contains("不要把计划本身当成答复");
        verify(planService).replace(eq(7L), eq(42L), eq(3L), any());
    }

    @Test
    void aFullySettledPlanClearsTheAgentToAnswer() {
        List<AgentPlanItem> saved = List.of(new AgentPlanItem("唯一一步", "DONE", ""));
        when(planService.replace(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(saved);
        when(planService.unfinished(saved)).thenReturn(List.of());

        JsonNode result = tool.execute(new AgentToolContext(42L, 7L, 1L), args(), new NoopProgress());

        assertThat(result.path("hint").asText()).contains("可以给出最终回复");
    }

    @Test
    void refusesToRunWithoutAConversationBecauseThePlanIsSessionState() {
        assertThatThrownBy(() -> tool.execute(42L, args(), new NoopProgress()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须在会话上下文中执行");
        assertThatThrownBy(() -> tool.execute(AgentToolContext.ofUser(42L), args(), new NoopProgress()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须在会话上下文中执行");
    }

    @Test
    void schemaAdvertisesTheFourStatusesAndRequiresItems() {
        JsonNode schema = tool.parametersSchema();

        assertThat(schema.path("required").toString()).isEqualTo("[\"items\"]");
        assertThat(schema.path("properties").path("items").path("items")
                .path("properties").path("status").path("enum").toString())
                .isEqualTo("[\"PENDING\",\"IN_PROGRESS\",\"DONE\",\"DROPPED\"]");
        assertThat(schema.path("properties").path("items").path("items")
                .path("required").toString()).isEqualTo("[\"title\",\"status\"]");
        assertThat(tool.name()).isEqualTo(PlanTasksTool.NAME);
        assertThat(tool.description()).contains("整体替换");
    }

    private ObjectNode args() {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.putArray("items").addObject().put("title", "查一下知识库").put("status", "DONE");
        return args;
    }

    private static final class NoopProgress implements AgentTool.ProgressListener {
        @Override
        public void onProgress(String message) {
        }
    }
}
