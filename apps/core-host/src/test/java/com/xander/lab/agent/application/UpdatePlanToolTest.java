package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.automation.api.BlogContentPlanUpdateRequest;
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
        when(planService.updateStatus(eq(42L), eq(1L), eq("PAUSED"))).thenReturn(paused);

        UpdatePlanTool tool = new UpdatePlanTool(planService);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("planId", 1)
                .put("action", "PAUSE");
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("action").asText()).isEqualTo("PAUSED");
        assertThat(result.path("topic").asText()).isEqualTo("Kubernetes 实战");
        assertThat(result.path("status").asText()).isEqualTo("PAUSED");
        assertThat(result.path("nextRunAt").asText()).isEqualTo("2026-08-17T01:00:00Z");
        verify(planService).updateStatus(eq(42L), eq(1L), eq("PAUSED"));
    }

    @Test
    void pausesPlanWithStatusVocabularyAlias() {
        BlogContentPlanService planService = mock(BlogContentPlanService.class);
        BlogContentPlanVO paused = BlogContentPlanVO.builder()
                .id(1L)
                .topic("Kubernetes 实战")
                .status("PAUSED")
                .build();
        when(planService.updateStatus(eq(42L), eq(1L), eq("PAUSED"))).thenReturn(paused);

        UpdatePlanTool tool = new UpdatePlanTool(planService);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("planId", 1)
                .put("action", "PAUSED");
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("action").asText()).isEqualTo("PAUSED");
        verify(planService).updateStatus(eq(42L), eq(1L), eq("PAUSED"));
    }

    @Test
    void cancelsPlanWithStatusVocabularyAlias() {
        BlogContentPlanService planService = mock(BlogContentPlanService.class);
        BlogContentPlanVO cancelled = BlogContentPlanVO.builder()
                .id(1L)
                .topic("前端下载链路")
                .status("CANCELLED")
                .build();
        when(planService.updateStatus(eq(42L), eq(1L), eq("CANCELLED"))).thenReturn(cancelled);

        UpdatePlanTool tool = new UpdatePlanTool(planService);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("planId", 1)
                .put("action", "CANCELLED");
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("action").asText()).isEqualTo("CANCELLED");
        assertThat(result.path("hint").asText()).contains("已取消");
        verify(planService).updateStatus(eq(42L), eq(1L), eq("CANCELLED"));
    }

    @Test
    void cancelsPlanWithActionVocabulary() {
        BlogContentPlanService planService = mock(BlogContentPlanService.class);
        BlogContentPlanVO cancelled = BlogContentPlanVO.builder()
                .id(1L)
                .topic("前端下载链路")
                .status("CANCELLED")
                .build();
        when(planService.updateStatus(eq(42L), eq(1L), eq("CANCELLED"))).thenReturn(cancelled);

        UpdatePlanTool tool = new UpdatePlanTool(planService);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("planId", 1)
                .put("action", "CANCEL");
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("action").asText()).isEqualTo("CANCELLED");
        verify(planService).updateStatus(eq(42L), eq(1L), eq("CANCELLED"));
    }

    @Test
    void editsPlanTopic() {
        BlogContentPlanService planService = mock(BlogContentPlanService.class);
        BlogContentPlanVO updated = BlogContentPlanVO.builder()
                .id(1L)
                .topic("前端文件下载体验与跨域兼容性实践")
                .status("ACTIVE")
                .build();
        when(planService.update(eq(42L), eq(1L), org.mockito.ArgumentMatchers.any(BlogContentPlanUpdateRequest.class)))
                .thenReturn(updated);

        UpdatePlanTool tool = new UpdatePlanTool(planService);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("planId", 1)
                .put("action", "EDIT")
                .put("topic", "前端文件下载体验与跨域兼容性实践");
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("action").asText()).isEqualTo("EDIT");
        assertThat(result.path("topic").asText()).isEqualTo("前端文件下载体验与跨域兼容性实践");
        assertThat(result.path("hint").asText()).contains("已更新");
        verify(planService).update(eq(42L), eq(1L), org.mockito.ArgumentMatchers.any(BlogContentPlanUpdateRequest.class));
    }

    @Test
    void rejectsOversizedTopicOnEdit() {
        BlogContentPlanService planService = mock(BlogContentPlanService.class);
        UpdatePlanTool tool = new UpdatePlanTool(planService);

        String longTopic = "下".repeat(121);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("planId", 1)
                .put("action", "EDIT")
                .put("topic", longTopic);
        assertThatThrownBy(() -> tool.execute(42L, args, new RecordingProgress()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不超过 120 字");
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
                .hasMessageContaining("PAUSE / PAUSED / RESUME / CANCEL / CANCELLED / DELETE / EDIT");
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