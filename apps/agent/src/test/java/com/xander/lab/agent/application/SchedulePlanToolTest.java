package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.automation.spi.ContentPlanDraft;
import com.xander.lab.automation.spi.ContentPlanPort;
import com.xander.lab.automation.spi.ContentPlanView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具对 {@link ContentPlanPort} 的行为。
 *
 * <p>⚠️ 这里捕获的是 {@link ContentPlanDraft} 而不是 {@code BlogContentPlanCreateRequest}。
 * 草稿是位置构造的，且里面 {@code topics} 与 {@code triggerTimes} 都是 {@code List<String>}、
 * {@code syncCsdn} 与 {@code syncJuejin} 都是 {@code Boolean} —— 互换不会有编译错误。
 * 所以断言逐个落在<b>具名访问器</b>上，并且给它们<b>互不相同的值</b>。</p>
 */
class SchedulePlanToolTest {

    @Test
    void createsPlanAndReportsNextRun() {
        ContentPlanPort planPort = mock(ContentPlanPort.class);
        ContentPlanView plan = new ContentPlanView(
                11L, "前端周报", "ACTIVE", "Asia/Shanghai",
                List.of("09:00", "20:00"), true, false, false,
                Instant.parse("2026-08-17T09:00:00Z"), null, null);
        when(planPort.create(eq(42L), any())).thenAnswer(invocation -> {
            ContentPlanDraft draft = invocation.getArgument(1);
            assertThat(draft.topic()).isEqualTo("前端周报");
            assertThat(draft.triggerTimes()).containsExactly("09:00", "20:00");
            assertThat(draft.topics()).isEmpty();
            assertThat(draft.timezone()).isEqualTo("Asia/Shanghai");
            // 只传了 syncCsdn，所以 syncJuejin 必须是 null（"没说"）而不是 false（"明确说不要"）。
            assertThat(draft.syncCsdn()).isTrue();
            assertThat(draft.syncJuejin()).isNull();
            return plan;
        });

        SchedulePlanTool tool = new SchedulePlanTool(planPort);
        JsonNodeFactory factory = JsonNodeFactory.instance;
        ObjectNode args = factory.objectNode()
                .put("topic", "前端周报")
                .put("syncCsdn", true);
        args.set("triggerTimes", factory.arrayNode().add("09:00").add("20:00"));
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("planId").asLong()).isEqualTo(11L);
        assertThat(result.path("triggerTimes")).hasSize(2);
        assertThat(result.path("status").asText()).isEqualTo("ACTIVE");
        verify(planPort).create(eq(42L), any());
    }

    @Test
    void rejectsMissingTopic() {
        ContentPlanPort planPort = mock(ContentPlanPort.class);
        SchedulePlanTool tool = new SchedulePlanTool(planPort);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("triggerTime", "09:00");
        assertThatThrownBy(() -> tool.execute(42L, args, new RecordingProgress()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topic");
    }

    @Test
    void forwardsInvocationIdAsTheCreateIdempotencyKeyAndReconcilesByIt() {
        ContentPlanPort planPort = mock(ContentPlanPort.class);
        ContentPlanView plan = new ContentPlanView(
                11L, "Weekly report", "ACTIVE", null,
                List.of("09:00"), null, null, null, null, null, null);
        when(planPort.create(eq(42L), any())).thenAnswer(invocation -> {
            ContentPlanDraft draft = invocation.getArgument(1);
            assertThat(draft.clientRequestId()).isEqualTo("invocation-2");
            return plan;
        });
        when(planPort.findByClientRequestId(42L, "invocation-2")).thenReturn(plan);

        SchedulePlanTool tool = new SchedulePlanTool(planPort);
        JsonNode args = JsonNodeFactory.instance.objectNode().put("topic", "Weekly report")
                .put("triggerTime", "09:00");
        AgentToolContext context = new AgentToolContext(42L, 3L, 9L, "invocation-2");

        tool.execute(context, args, new RecordingProgress());
        JsonNode reconciled = tool.reconcile(context, args).orElseThrow();

        assertThat(reconciled.path("planId").asLong()).isEqualTo(11L);
        verify(planPort).findByClientRequestId(42L, "invocation-2");
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
