package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.automation.api.EmailReminderTaskVO;
import com.xander.lab.automation.application.EmailReminderService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryEmailRemindersToolTest {

    @Test
    void returnsAllRemindersInOneCall() {
        EmailReminderService emailReminderService = mock(EmailReminderService.class);
        EmailReminderTaskVO pending = EmailReminderTaskVO.builder()
                .id(1L)
                .recipientEmail("alice@example.com")
                .subject("每周技术周报")
                .frequency("WEEKLY")
                .status("PENDING")
                .timezone("Asia/Shanghai")
                .sendTime("09:00")
                .scheduledAt(Instant.parse("2026-08-17T01:00:00Z"))
                .build();
        EmailReminderTaskVO failed = EmailReminderTaskVO.builder()
                .id(2L)
                .recipientEmail("bob@example.com")
                .subject("月度报告提醒")
                .frequency("ONCE")
                .status("FAILED")
                .timezone("Asia/Shanghai")
                .errorMessage("SMTP 连接超时")
                .build();
        when(emailReminderService.listAll(eq(42L), eq(""), eq("")))
                .thenReturn(List.of(pending, failed));

        QueryEmailRemindersTool tool = new QueryEmailRemindersTool(emailReminderService);
        JsonNode result = tool.execute(42L, JsonNodeFactory.instance.objectNode(), new RecordingProgress());

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("total").asLong()).isEqualTo(2L);
        assertThat(result.path("reminders")).hasSize(2);
        JsonNode first = result.path("reminders").get(0);
        assertThat(first.path("id").asLong()).isEqualTo(1L);
        assertThat(first.path("recipientEmail").asText()).isEqualTo("alice@example.com");
        assertThat(first.path("subject").asText()).isEqualTo("每周技术周报");
        assertThat(first.path("frequency").asText()).isEqualTo("WEEKLY");
        assertThat(first.path("status").asText()).isEqualTo("PENDING");
        assertThat(first.path("scheduledAt").asText()).isEqualTo("2026-08-17T01:00:00Z");
        assertThat(first.path("errorMessage").isMissingNode()).isTrue();
        assertThat(result.path("reminders").get(1).path("errorMessage").asText()).isEqualTo("SMTP 连接超时");
        assertThat(result.path("page").isMissingNode()).isTrue();
        verify(emailReminderService).listAll(eq(42L), eq(""), eq(""));
    }

    @Test
    void passesStatusAndSearchThrough() {
        EmailReminderService emailReminderService = mock(EmailReminderService.class);
        when(emailReminderService.listAll(eq(42L), eq("SENT"), eq("周报"))).thenReturn(List.of());

        QueryEmailRemindersTool tool = new QueryEmailRemindersTool(emailReminderService);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("status", "SENT")
                .put("search", "周报");
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("total").asLong()).isEqualTo(0L);
        verify(emailReminderService).listAll(eq(42L), eq("SENT"), eq("周报"));
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