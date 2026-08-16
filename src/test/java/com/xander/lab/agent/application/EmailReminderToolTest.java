package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.automation.api.EmailReminderCreateRequest;
import com.xander.lab.automation.api.EmailReminderTaskVO;
import com.xander.lab.automation.application.EmailReminderService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailReminderToolTest {

    @Test
    void createsDailyReminderAndReportsScheduledAt() {
        EmailReminderService service = mock(EmailReminderService.class);
        EmailReminderTaskVO task = EmailReminderTaskVO.builder()
                .id(7L)
                .recipientEmail("me@example.com")
                .subject("每日复盘")
                .frequency("DAILY")
                .status("ACTIVE")
                .build();
        when(service.create(eq(42L), any())).thenAnswer(invocation -> {
            EmailReminderCreateRequest request = invocation.getArgument(1);
            assertThat(request.getSendTime()).isEqualTo("09:00");
            assertThat(request.getTimezone()).isEqualTo("Asia/Shanghai");
            assertThat(request.getRecurrenceDay()).isEqualTo(1);
            return task;
        });

        EmailReminderTool tool = new EmailReminderTool(service);
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
        verify(service).create(eq(42L), any());
    }

    @Test
    void rejectsMissingRecipient() {
        EmailReminderService service = mock(EmailReminderService.class);
        EmailReminderTool tool = new EmailReminderTool(service);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("subject", "主题")
                .put("message", "正文");
        assertThatThrownBy(() -> tool.execute(42L, args, new RecordingProgress()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recipientEmail");
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