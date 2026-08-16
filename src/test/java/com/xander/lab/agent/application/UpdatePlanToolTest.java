package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.automation.api.BlogContentPlanVO;
import com.xander.lab.automation.application.BlogContentPlanService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UpdatePlanToolTest {

    @Test
    void pausesPlanAndReturnsNewState() {
        BlogContentPlanService planService = mock(BlogContentPlanService.class);
        BlogContentPlanVO paused = BlogContentPlanVO.builder()
                .id(1L)
                .topic("Kubernetes 实战")
                .status("PAUSED")
                .nextRunAt(Instant.parse("2026-08-17T01:00:00Z"))
                .build();
        when(planService.updateStatus(eq(42L), eq(1L), eq("PAUSE"))).thenReturn(paused);

        UpdatePlanTool tool = new UpdatePlanTool(planService);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("planId", 1)
                .put("action", "PAUSE");
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("action").asText()).isEqualTo("PAUSE");
        assertThat(result.path("topic").asText()).isEqualTo("Kubernetes 实战");
        assertThat(result.path("status").asText()).isEqualTo("PAUSED");
        assertThat(result.path("nextRunAt").asText()).isEqualTo("2026-08-17T01:00:00Z");
        verify(planService).updateStatus(eq(42L), eq(1L), eq("PAUSE"));
    }

    @Test
    void deletesPlan() {
        BlogContentPlanService planService = mock(BlogContentPlanService.class);

        UpdatePlanTool tool = new UpdatePlanTool(planService);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("planId", 2)
                .put("action", "DELETE");
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("action").asText()).isEqualTo("DELETE");
        assertThat(result.path("hint").asText()).contains("已删除");
        verify(planService).delete(eq(42L), eq(2L));
    }

    @Test
    void rejectsMissingOrUnsupportedArguments() {
        BlogContentPlanService planService = mock(BlogContentPlanService.class);
        UpdatePlanTool tool = new UpdatePlanTool(planService);

        assertThatThrownBy(() -> tool.execute(42L, JsonNodeFactory.instance.objectNode(), new RecordingProgress()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("planId");
        JsonNode badAction = JsonNodeFactory.instance.objectNode().put("planId", 1).put("action", "RUN");
        assertThatThrownBy(() -> tool.execute(42L, badAction, new RecordingProgress()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PAUSE / RESUME / CANCEL / DELETE");
    }

    private static final class RecordingProgress implements AgentTool.ProgressListener {
        @Override
        public void onProgress(String message) {
        }

        @Override
        public void onToolDelta(String delta) {
        }
    }
}