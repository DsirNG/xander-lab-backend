package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.automation.spi.ContentPlanPort;
import com.xander.lab.automation.spi.ContentPlanView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具对 {@link ContentPlanPort} 的行为。mock 端口而不是 {@code BlogContentPlanService}，
 * 是为了让本类同时证明"工具只需要端口"。
 */
class QueryPlansToolTest {

    @Test
    void returnsAllPlansInOneCall() {
        ContentPlanPort planPort = mock(ContentPlanPort.class);
        ContentPlanView active = new ContentPlanView(
                1L, "Kubernetes 实战", "ACTIVE", "Asia/Shanghai",
                List.of("09:00", "20:00"), true, false, false,
                Instant.parse("2026-08-17T01:00:00Z"), null, null);
        ContentPlanView failed = new ContentPlanView(
                2L, "Docker 网络", "FAILED", "Asia/Shanghai",
                List.of("09:00"), null, null, null,
                null, null, "模型调用超时");
        when(planPort.listAll(eq(42L), eq(""))).thenReturn(List.of(active, failed));

        QueryPlansTool tool = new QueryPlansTool(planPort);
        JsonNode result = tool.execute(42L, JsonNodeFactory.instance.objectNode(), new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("total").asLong()).isEqualTo(2L);
        assertThat(result.path("plans")).hasSize(2);
        JsonNode first = result.path("plans").get(0);
        assertThat(first.path("id").asLong()).isEqualTo(1L);
        assertThat(first.path("topic").asText()).isEqualTo("Kubernetes 实战");
        assertThat(first.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(first.path("timezone").asText()).isEqualTo("Asia/Shanghai");
        assertThat(first.path("syncCsdn").asBoolean()).isTrue();
        assertThat(first.path("syncJuejin").asBoolean()).isFalse();
        assertThat(first.path("runOnce").asBoolean()).isFalse();
        assertThat(first.path("triggerTimes")).hasSize(2);
        assertThat(first.path("nextRunAt").asText()).isEqualTo("2026-08-17T01:00:00Z");
        assertThat(first.path("lastRunAt").asText()).isEmpty();
        assertThat(first.path("errorMessage").isMissingNode()).isTrue();
        // 第二项的三个 Boolean 都是 null：必须落成 false 而不是让 Jackson 写 null。
        JsonNode second = result.path("plans").get(1);
        assertThat(second.path("syncCsdn").asBoolean()).isFalse();
        assertThat(second.path("runOnce").asBoolean()).isFalse();
        assertThat(second.path("errorMessage").asText()).isEqualTo("模型调用超时");
        assertThat(result.path("page").isMissingNode()).isTrue();
        verify(planPort).listAll(eq(42L), eq(""));
    }

    @Test
    void passesStatusFilterThrough() {
        ContentPlanPort planPort = mock(ContentPlanPort.class);
        when(planPort.listAll(eq(42L), eq("FAILED"))).thenReturn(List.of());

        QueryPlansTool tool = new QueryPlansTool(planPort);
        JsonNode args = JsonNodeFactory.instance.objectNode().put("status", "FAILED");
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("total").asLong()).isEqualTo(0L);
        assertThat(result.path("plans")).hasSize(0);
        verify(planPort).listAll(eq(42L), eq("FAILED"));
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
