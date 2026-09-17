package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.automation.spi.EmailReminderPort;
import com.xander.lab.automation.spi.EmailReminderView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具对 {@link EmailReminderPort} 的行为。
 *
 * <p>这里 mock 的是<b>端口</b>而不是 {@code EmailReminderService} —— 这正是这次切分要证明的
 * 命题：工具只需要端口这一组操作，不需要知道 automation 域的实现类。
 * 如果哪天有人把工具改回依赖实现类，本类会因为构造器参数对不上而编译失败。</p>
 *
 * <p>断言全部落在工具输出的 JSON 字段上：这是对外契约（模型看到的就是它），
 * 换成 record 之后必须逐字段不变。</p>
 */
class QueryEmailRemindersToolTest {

    @Test
    void returnsAllRemindersInOneCall() {
        EmailReminderPort reminderPort = mock(EmailReminderPort.class);
        EmailReminderView pending = new EmailReminderView(
                1L,
                "alice@example.com",
                "每周技术周报",
                "本周进展……",
                "WEEKLY",
                "Asia/Shanghai",
                "09:00",
                Instant.parse("2026-08-17T01:00:00Z"),
                "PENDING",
                null,
                null);
        EmailReminderView failed = new EmailReminderView(
                2L,
                "bob@example.com",
                "月度报告提醒",
                "记得交报告",
                "ONCE",
                "Asia/Shanghai",
                null,
                null,
                "FAILED",
                "SMTP 连接超时",
                null);
        when(reminderPort.listAll(eq(42L), eq(""), eq("")))
                .thenReturn(List.of(pending, failed));

        QueryEmailRemindersTool tool = new QueryEmailRemindersTool(reminderPort);
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
        assertThat(first.path("timezone").asText()).isEqualTo("Asia/Shanghai");
        // sendTime 与 scheduledAt 是相邻的两个"时间"字段，但一个来自本地时刻、一个是绝对时刻。
        // 断言它单独一行，是因为契约最初漏掉过 sendTime —— 漏掉时工具输出会静默少一个键。
        assertThat(first.path("sendTime").asText()).isEqualTo("09:00");
        assertThat(first.path("scheduledAt").asText()).isEqualTo("2026-08-17T01:00:00Z");
        assertThat(first.path("sentAt").asText()).isEmpty();
        assertThat(first.path("errorMessage").isMissingNode()).isTrue();
        assertThat(result.path("reminders").get(1).path("errorMessage").asText()).isEqualTo("SMTP 连接超时");
        assertThat(result.path("page").isMissingNode()).isTrue();
        verify(reminderPort).listAll(eq(42L), eq(""), eq(""));
    }

    @Test
    void passesStatusAndSearchThrough() {
        EmailReminderPort reminderPort = mock(EmailReminderPort.class);
        when(reminderPort.listAll(eq(42L), eq("SENT"), eq("周报"))).thenReturn(List.of());

        QueryEmailRemindersTool tool = new QueryEmailRemindersTool(reminderPort);
        JsonNode args = JsonNodeFactory.instance.objectNode()
                .put("status", "SENT")
                .put("search", "周报");
        JsonNode result = tool.execute(42L, args, new RecordingProgress());

        assertThat(result.path("total").asLong()).isEqualTo(0L);
        verify(reminderPort).listAll(eq(42L), eq("SENT"), eq("周报"));
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
