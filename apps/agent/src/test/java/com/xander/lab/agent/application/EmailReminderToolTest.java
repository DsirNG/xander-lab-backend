package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.automation.spi.EmailReminderDraft;
import com.xander.lab.automation.spi.EmailReminderPort;
import com.xander.lab.automation.spi.EmailReminderView;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具对 {@link EmailReminderPort} 的行为。
 *
 * <p>⚠️ 这里捕获的是 {@link EmailReminderDraft} 而不是 {@code EmailReminderCreateRequest}。
 * 两者字段名大体相同、分量顺序<b>不同</b>（草稿里 {@code scheduledAt} 在 {@code sendTime} 之前），
 * 而草稿是位置构造的 —— 所以断言必须落在<b>具名访问器</b>上，
 * 写 {@code invocation.getArgument(1)} 之后逐个字段核对，顺序错了才会被抓住。</p>
 */
class EmailReminderToolTest {

    @Test
    void createsDailyReminderAndReportsScheduledAt() {
        EmailReminderPort reminderPort = mock(EmailReminderPort.class);
        EmailReminderView created = new EmailReminderView(
                7L, "me@example.com", "每日复盘", "今天写点东西", "DAILY",
                "Asia/Shanghai", "09:00", null, "ACTIVE", null, null);
        when(reminderPort.create(eq(42L), any())).thenAnswer(invocation -> {
            EmailReminderDraft draft = invocation.getArgument(1);
            assertThat(draft.recipientEmail()).isEqualTo("me@example.com");
            assertThat(draft.subject()).isEqualTo("每日复盘");
            assertThat(draft.frequency()).isEqualTo("DAILY");
            assertThat(draft.sendTime()).isEqualTo("09:00");
            assertThat(draft.timezone()).isEqualTo("Asia/Shanghai");
            assertThat(draft.recurrenceDay()).isEqualTo(1);
            return created;
        });

        EmailReminderTool tool = new EmailReminderTool(reminderPort);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("recipientEmail", "me@example.com")
                .put("subject", "每日复盘")
                .put("message", "今天写点东西")
                .put("frequency", "DAILY")
                .put("sendTime", "09:00")
                .put("recurrenceDay", 1);
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("taskId").asLong()).isEqualTo(7L);
        assertThat(result.path("recipientEmail").asText()).isEqualTo("me@example.com");
        assertThat(result.path("frequency").asText()).isEqualTo("DAILY");
        verify(reminderPort).create(eq(42L), any());
    }

    @Test
    void rejectsMissingRecipient() {
        EmailReminderPort reminderPort = mock(EmailReminderPort.class);
        EmailReminderTool tool = new EmailReminderTool(reminderPort);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("subject", "主题")
                .put("message", "正文");
        assertThatThrownBy(() -> tool.execute(42L, args, new RecordingProgress()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recipientEmail");
    }

    @Test
    void forwardsInvocationIdAsTheCreateIdempotencyKeyAndReconcilesByIt() {
        EmailReminderPort reminderPort = mock(EmailReminderPort.class);
        EmailReminderView created = new EmailReminderView(
                7L, "me@example.com", "Reminder", "Body", "ONCE",
                null, null, null, "PENDING", null, null);
        when(reminderPort.create(eq(42L), any())).thenAnswer(invocation -> {
            EmailReminderDraft draft = invocation.getArgument(1);
            assertThat(draft.clientRequestId()).isEqualTo("invocation-1");
            return created;
        });
        when(reminderPort.findByClientRequestId(42L, "invocation-1")).thenReturn(created);

        EmailReminderTool tool = new EmailReminderTool(reminderPort);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("recipientEmail", "me@example.com")
                .put("subject", "Reminder")
                .put("message", "Body");
        AgentToolContext context = new AgentToolContext(42L, 3L, 9L, "invocation-1");

        tool.execute(context, args, new RecordingProgress());
        JsonNode reconciled = tool.reconcile(context, args).orElseThrow();

        assertThat(reconciled.path("taskId").asLong()).isEqualTo(7L);
        verify(reminderPort).findByClientRequestId(42L, "invocation-1");
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
