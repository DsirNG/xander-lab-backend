package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.automation.spi.ContentPlanEdit;
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
 * 工具对 {@link ContentPlanPort} 的行为。mock 端口而不是 {@code BlogContentPlanService}，
 * 是为了让本类同时证明"工具只需要端口"。
 */
class UpdatePlanToolTest {

    @Test
    void pausesPlanAndReturnsNewState() {
        ContentPlanPort planPort = mock(ContentPlanPort.class);
        when(planPort.updateStatus(eq(42L), eq(1L), eq("PAUSED")))
                .thenReturn(view(1L, "Kubernetes 实战", "PAUSED", Instant.parse("2026-08-17T01:00:00Z"), null));

        UpdatePlanTool tool = new UpdatePlanTool(planPort);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("planId", 1)
                .put("action", "PAUSE");
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("action").asText()).isEqualTo("PAUSED");
        assertThat(result.path("topic").asText()).isEqualTo("Kubernetes 实战");
        assertThat(result.path("status").asText()).isEqualTo("PAUSED");
        assertThat(result.path("nextRunAt").asText()).isEqualTo("2026-08-17T01:00:00Z");
        verify(planPort).updateStatus(eq(42L), eq(1L), eq("PAUSED"));
    }

    @Test
    void pausesPlanWithStatusVocabularyAlias() {
        ContentPlanPort planPort = mock(ContentPlanPort.class);
        when(planPort.updateStatus(eq(42L), eq(1L), eq("PAUSED")))
                .thenReturn(view(1L, "Kubernetes 实战", "PAUSED", null, null));

        UpdatePlanTool tool = new UpdatePlanTool(planPort);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("planId", 1)
                .put("action", "PAUSED");
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("action").asText()).isEqualTo("PAUSED");
        verify(planPort).updateStatus(eq(42L), eq(1L), eq("PAUSED"));
    }

    @Test
    void cancelsPlanWithStatusVocabularyAlias() {
        ContentPlanPort planPort = mock(ContentPlanPort.class);
        when(planPort.updateStatus(eq(42L), eq(1L), eq("CANCELLED")))
                .thenReturn(view(1L, "前端下载链路", "CANCELLED", null, null));

        UpdatePlanTool tool = new UpdatePlanTool(planPort);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("planId", 1)
                .put("action", "CANCELLED");
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("action").asText()).isEqualTo("CANCELLED");
        assertThat(result.path("hint").asText()).contains("已取消");
        verify(planPort).updateStatus(eq(42L), eq(1L), eq("CANCELLED"));
    }

    @Test
    void cancelsPlanWithActionVocabulary() {
        ContentPlanPort planPort = mock(ContentPlanPort.class);
        when(planPort.updateStatus(eq(42L), eq(1L), eq("CANCELLED")))
                .thenReturn(view(1L, "前端下载链路", "CANCELLED", null, null));

        UpdatePlanTool tool = new UpdatePlanTool(planPort);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("planId", 1)
                .put("action", "CANCEL");
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("action").asText()).isEqualTo("CANCELLED");
        verify(planPort).updateStatus(eq(42L), eq(1L), eq("CANCELLED"));
    }

    @Test
    void editsPlanTopic() {
        ContentPlanPort planPort = mock(ContentPlanPort.class);
        when(planPort.update(eq(42L), eq(1L), any(ContentPlanEdit.class)))
                .thenReturn(view(1L, "前端文件下载体验与跨域兼容性实践", "ACTIVE", null, null));

        UpdatePlanTool tool = new UpdatePlanTool(planPort);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("planId", 1)
                .put("action", "EDIT")
                .put("topic", "前端文件下载体验与跨域兼容性实践");
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("action").asText()).isEqualTo("EDIT");
        assertThat(result.path("topic").asText()).isEqualTo("前端文件下载体验与跨域兼容性实践");
        assertThat(result.path("hint").asText()).contains("已更新");
        verify(planPort).update(eq(42L), eq(1L), any(ContentPlanEdit.class));
    }

    /**
     * ⚠️ 本类最重要的一条：<b>没传</b>与<b>传了空</b>不是一回事。
     *
     * <p>EDIT 的语义是"只改提交了的字段"。换成 record 之后，如果有人在换算时用
     * {@code defaultText} 给 {@code topic} 兜一个空串、或用 {@code false} 兜布尔值，
     * 就会把"用户没说"变成"用户要求改成空/关掉"，而这条路径不会有任何编译错误。
     * 所以这里显式断言这些分量是 {@code null}。</p>
     */
    @Test
    void editLeavesUnspecifiedFieldsNull() {
        ContentPlanPort planPort = mock(ContentPlanPort.class);
        when(planPort.update(eq(42L), eq(1L), any(ContentPlanEdit.class)))
                .thenAnswer(invocation -> {
                    ContentPlanEdit edit = invocation.getArgument(2);
                    assertThat(edit.topic()).isEqualTo("新主题");
                    assertThat(edit.syncCsdn()).isNull();
                    assertThat(edit.syncJuejin()).isNull();
                    assertThat(edit.timezone()).isNull();
                    assertThat(edit.audience()).isNull();
                    assertThat(edit.tone()).isNull();
                    assertThat(edit.triggerTime()).isNull();
                    assertThat(edit.triggerTimes()).isEmpty();
                    return view(1L, "新主题", "ACTIVE", null, null);
                });

        UpdatePlanTool tool = new UpdatePlanTool(planPort);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("planId", 1)
                .put("action", "EDIT")
                .put("topic", "新主题");
        tool.execute(42L, args, new RecordingProgress());

        verify(planPort).update(eq(42L), eq(1L), any(ContentPlanEdit.class));
    }

    /** 显式传了 false 时必须落成 false，不能被当成"没说"。 */
    @Test
    void editCarriesExplicitFalseBooleans() {
        ContentPlanPort planPort = mock(ContentPlanPort.class);
        when(planPort.update(eq(42L), eq(1L), any(ContentPlanEdit.class)))
                .thenAnswer(invocation -> {
                    ContentPlanEdit edit = invocation.getArgument(2);
                    assertThat(edit.syncCsdn()).isFalse();
                    assertThat(edit.syncJuejin()).isTrue();
                    return view(1L, "主题", "ACTIVE", null, null);
                });

        UpdatePlanTool tool = new UpdatePlanTool(planPort);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("planId", 1)
                .put("action", "EDIT")
                .put("syncCsdn", false)
                .put("syncJuejin", true);
        tool.execute(42L, args, new RecordingProgress());

        verify(planPort).update(eq(42L), eq(1L), any(ContentPlanEdit.class));
    }

    @Test
    void rejectsOversizedTopicOnEdit() {
        ContentPlanPort planPort = mock(ContentPlanPort.class);
        UpdatePlanTool tool = new UpdatePlanTool(planPort);

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
        ContentPlanPort planPort = mock(ContentPlanPort.class);

        UpdatePlanTool tool = new UpdatePlanTool(planPort);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("planId", 2)
                .put("action", "DELETE");
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("action").asText()).isEqualTo("DELETE");
        assertThat(result.path("hint").asText()).contains("已删除");
        verify(planPort).delete(eq(42L), eq(2L));
    }

    @Test
    void rejectsMissingOrUnsupportedArguments() {
        ContentPlanPort planPort = mock(ContentPlanPort.class);
        UpdatePlanTool tool = new UpdatePlanTool(planPort);

        assertThatThrownBy(() -> tool.execute(42L, JsonNodeFactory.instance.objectNode(), new RecordingProgress()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("planId");
        JsonNode badAction = JsonNodeFactory.instance.objectNode().put("planId", 1).put("action", "RUN");
        assertThatThrownBy(() -> tool.execute(42L, badAction, new RecordingProgress()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PAUSE / PAUSED / RESUME / CANCEL / CANCELLED / DELETE / EDIT");
    }

    /** 只填本类关心的分量，其余留 null。 */
    private static ContentPlanView view(Long id, String topic, String status, Instant nextRunAt, String errorMessage) {
        return new ContentPlanView(
                id, topic, status, null, List.of(), null, null, null, nextRunAt, null, errorMessage);
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
