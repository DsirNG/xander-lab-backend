package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.automation.spi.EmailReminderPort;
import com.xander.lab.automation.spi.EmailReminderView;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具对 {@link EmailReminderPort} 的行为。
 *
 * <p>mock 端口而不是 {@code EmailReminderService}，是为了让本类同时证明"工具只需要端口"。
 * 两个状态常量也从实现类搬到了端口上：断言里写 {@link EmailReminderPort#STATUS_PAUSED}，
 * 而不是 {@code EmailReminderService.STATUS_PAUSED} —— 后者会要求测试 import 实现类，
 * 那条 import 就是拆服务后第一个编译不过的地方。</p>
 */
class UpdateEmailReminderToolTest {

    @Test
    void pausesReminderAndReturnsNewState() {
        EmailReminderPort reminderPort = mock(EmailReminderPort.class);
        when(reminderPort.updateStatus(eq(42L), eq(1L), eq(EmailReminderPort.STATUS_PAUSED)))
                .thenReturn(view(1L, "alice@example.com", "每周技术周报", "PAUSED",
                        Instant.parse("2026-08-17T01:00:00Z")));

        UpdateEmailReminderTool tool = new UpdateEmailReminderTool(reminderPort);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("taskId", 1)
                .put("action", "PAUSE");
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("action").asText()).isEqualTo("PAUSED");
        assertThat(result.path("recipientEmail").asText()).isEqualTo("alice@example.com");
        assertThat(result.path("subject").asText()).isEqualTo("每周技术周报");
        assertThat(result.path("status").asText()).isEqualTo("PAUSED");
        assertThat(result.path("scheduledAt").asText()).isEqualTo("2026-08-17T01:00:00Z");
        verify(reminderPort).updateStatus(eq(42L), eq(1L), eq(EmailReminderPort.STATUS_PAUSED));
    }

    @Test
    void pausesReminderWithStatusVocabularyAlias() {
        EmailReminderPort reminderPort = mock(EmailReminderPort.class);
        when(reminderPort.updateStatus(eq(42L), eq(1L), eq(EmailReminderPort.STATUS_PAUSED)))
                .thenReturn(view(1L, "alice@example.com", "每周技术周报", "PAUSED", null));

        UpdateEmailReminderTool tool = new UpdateEmailReminderTool(reminderPort);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("taskId", 1)
                .put("action", "PAUSED");
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("action").asText()).isEqualTo("PAUSED");
        // scheduledAt 为 null 时必须落成空串而不是 "null"，否则模型会读到字面量 "null"。
        assertThat(result.path("scheduledAt").asText()).isEmpty();
        verify(reminderPort).updateStatus(eq(42L), eq(1L), eq(EmailReminderPort.STATUS_PAUSED));
    }

    @Test
    void resumeMapsToPendingStatus() {
        EmailReminderPort reminderPort = mock(EmailReminderPort.class);
        when(reminderPort.updateStatus(eq(42L), eq(1L), eq(EmailReminderPort.STATUS_PENDING)))
                .thenReturn(view(1L, "alice@example.com", "每周技术周报", "PENDING", null));

        UpdateEmailReminderTool tool = new UpdateEmailReminderTool(reminderPort);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("taskId", 1)
                .put("action", "RESUME");
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("action").asText()).isEqualTo("RESUME");
        assertThat(result.path("status").asText()).isEqualTo("PENDING");
        verify(reminderPort).updateStatus(eq(42L), eq(1L), eq(EmailReminderPort.STATUS_PENDING));
    }

    @Test
    void deletesReminder() {
        EmailReminderPort reminderPort = mock(EmailReminderPort.class);

        UpdateEmailReminderTool tool = new UpdateEmailReminderTool(reminderPort);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("taskId", 2)
                .put("action", "DELETE");
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("hint").asText()).contains("已删除");
        verify(reminderPort).delete(eq(42L), eq(2L));
    }

    @Test
    void rejectsMissingOrUnsupportedArguments() {
        EmailReminderPort reminderPort = mock(EmailReminderPort.class);
        UpdateEmailReminderTool tool = new UpdateEmailReminderTool(reminderPort);

        assertThatThrownBy(() -> tool.execute(42L, JsonNodeFactory.instance.objectNode(), new RecordingProgress()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
        JsonNode badAction = JsonNodeFactory.instance.objectNode().put("taskId", 1).put("action", "RUN");
        assertThatThrownBy(() -> tool.execute(42L, badAction, new RecordingProgress()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PAUSE / PAUSED / RESUME / DELETE");
    }

    /** 只填本类关心的分量，其余留 null —— 工具输出只读这四个。 */
    private static EmailReminderView view(Long id, String recipientEmail, String subject,
                                         String status, Instant scheduledAt) {
        return new EmailReminderView(
                id, recipientEmail, subject, null, null, null, null, scheduledAt, status, null, null);
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
