package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.automation.api.BlogContentPlanVO;
import com.xander.lab.automation.application.BlogContentPlanService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryPlansToolTest {

    @Test
    void returnsAllPlansInOneCall() {
        BlogContentPlanService planService = mock(BlogContentPlanService.class);
        BlogContentPlanVO active = BlogContentPlanVO.builder()
                .id(1L)
                .topic("Kubernetes 实战")
                .status("ACTIVE")
                .timezone("Asia/Shanghai")
                .triggerTimes(List.of("09:00", "20:00"))
                .syncCsdn(true)
                .runOnce(false)
                .nextRunAt(Instant.parse("2026-08-17T01:00:00Z"))
                .build();
        BlogContentPlanVO failed = BlogContentPlanVO.builder()
                .id(2L)
                .topic("Docker 网络")
                .status("FAILED")
                .timezone("Asia/Shanghai")
                .triggerTimes(List.of("09:00"))
                .errorMessage("模型调用超时")
                .build();
        when(planService.listAll(eq(42L), eq(""))).thenReturn(List.of(active, failed));

        QueryPlansTool tool = new QueryPlansTool(planService);
        JsonNode result = tool.execute(42L, JsonNodeFactory.instance.objectNode(), new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("total").asLong()).isEqualTo(2L);
        assertThat(result.path("plans")).hasSize(2);
        JsonNode first = result.path("plans").get(0);
        assertThat(first.path("id").asLong()).isEqualTo(1L);
        assertThat(first.path("topic").asText()).isEqualTo("Kubernetes 实战");
        assertThat(first.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(first.path("syncCsdn").asBoolean()).isTrue();
        assertThat(first.path("runOnce").asBoolean()).isFalse();
        assertThat(first.path("triggerTimes")).hasSize(2);
        assertThat(first.path("nextRunAt").asText()).isEqualTo("2026-08-17T01:00:00Z");
        assertThat(first.path("errorMessage").isMissingNode()).isTrue();
        assertThat(result.path("plans").get(1).path("errorMessage").asText()).isEqualTo("模型调用超时");
        assertThat(result.path("page").isMissingNode()).isTrue();
        verify(planService).listAll(eq(42L), eq(""));
    }

    @Test
    void passesStatusFilterThrough() {
        BlogContentPlanService planService = mock(BlogContentPlanService.class);
        when(planService.listAll(eq(42L), eq("FAILED"))).thenReturn(List.of());

        QueryPlansTool tool = new QueryPlansTool(planService);
        JsonNode args = JsonNodeFactory.instance.objectNode().put("status", "FAILED");
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("total").asLong()).isEqualTo(0L);
        assertThat(result.path("plans")).hasSize(0);
        verify(planService).listAll(eq(42L), eq("FAILED"));
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