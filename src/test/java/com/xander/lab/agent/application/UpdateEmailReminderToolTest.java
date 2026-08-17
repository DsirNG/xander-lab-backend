package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.automation.api.EmailReminderTaskVO;
import com.xander.lab.automation.application.EmailReminderService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UpdateEmailReminderToolTest {

    @Test
    void pausesReminderAndReturnsNewState() {
        EmailReminderService emailReminderService = mock(EmailReminderService.class);
        EmailReminderTaskVO paused = EmailReminderTaskVO.builder()
                .id(1L)
                .recipientEmail("alice@example.com")
                .subject("每周技术周报")
                .status("PAUSED")
                .scheduledAt(Instant.parse("2026-08-17T01:00:00Z"))
                .build();
        when(emailReminderService.updateStatus(eq(42L), eq(1L), eq(EmailReminderService.STATUS_PAUSED)))
                .thenReturn(paused);

        UpdateEmailReminderTool tool = new UpdateEmailReminderTool(emailReminderService);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("taskId", 1)
                .put("action", "PAUSE");
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("action").asText()).isEqualTo("PAUSED");
        assertThat(result.path("recipientEmail").asText()).isEqualTo("alice@example.com");
        assertThat(result.path("status").asText()).isEqualTo("PAUSED");
        verify(emailReminderService).updateStatus(eq(42L), eq(1L), eq(EmailReminderService.STATUS_PAUSED));
    }

    @Test
    void pausesReminderWithStatusVocabularyAlias() {
        EmailReminderService emailReminderService = mock(EmailReminderService.class);
        EmailReminderTaskVO paused = EmailReminderTaskVO.builder()
                .id(1L)
                .recipientEmail("alice@example.com")
                .subject("每周技术周报")
                .status("PAUSED")
                .build();
        when(emailReminderService.updateStatus(eq(42L), eq(1L), eq(EmailReminderService.STATUS_PAUSED)))
                .thenReturn(paused);

        UpdateEmailReminderTool tool = new UpdateEmailReminderTool(emailReminderService);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("taskId", 1)
                .put("action", "PAUSED");
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("action").asText()).isEqualTo("PAUSED");
        verify(emailReminderService).updateStatus(eq(42L), eq(1L), eq(EmailReminderService.STATUS_PAUSED));
    }

    @Test
    void resumeMapsToPendingStatus() {
        EmailReminderService emailReminderService = mock(EmailReminderService.class);
        EmailReminderTaskVO pending = EmailReminderTaskVO.builder()
                .id(1L)
                .recipientEmail("alice@example.com")
                .subject("每周技术周报")
                .status("PENDING")
                .build();
        when(emailReminderService.updateStatus(eq(42L), eq(1L), eq(EmailReminderService.STATUS_PENDING)))
                .thenReturn(pending);

        UpdateEmailReminderTool tool = new UpdateEmailReminderTool(emailReminderService);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("taskId", 1)
                .put("action", "RESUME");
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("action").asText()).isEqualTo("RESUME");
        assertThat(result.path("status").asText()).isEqualTo("PENDING");
        verify(emailReminderService).updateStatus(eq(42L), eq(1L), eq(EmailReminderService.STATUS_PENDING));
    }

    @Test
    void deletesReminder() {
        EmailReminderService emailReminderService = mock(EmailReminderService.class);

        UpdateEmailReminderTool tool = new UpdateEmailReminderTool(emailReminderService);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("taskId", 2)
                .put("action", "DELETE");
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("hint").asText()).contains("已删除");
        verify(emailReminderService).delete(eq(42L), eq(2L));
    }

    @Test
    void rejectsMissingOrUnsupportedArguments() {
        EmailReminderService emailReminderService = mock(EmailReminderService.class);
        UpdateEmailReminderTool tool = new UpdateEmailReminderTool(emailReminderService);

        assertThatThrownBy(() -> tool.execute(42L, JsonNodeFactory.instance.objectNode(), new RecordingProgress()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
        JsonNode badAction = JsonNodeFactory.instance.objectNode().put("taskId", 1).put("action", "RUN");
        assertThatThrownBy(() -> tool.execute(42L, badAction, new RecordingProgress()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PAUSE / PAUSED / RESUME / DELETE");
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