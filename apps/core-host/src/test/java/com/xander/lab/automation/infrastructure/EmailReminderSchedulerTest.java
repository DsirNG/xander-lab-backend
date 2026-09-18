package com.xander.lab.automation.infrastructure;

import com.xander.lab.automation.domain.EmailReminderTask;
import com.xander.lab.automation.infrastructure.EmailReminderTaskMapper;
import com.xander.lab.payment.api.BillingPort;
import com.xander.lab.integration.api.MailPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailReminderSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-07-24T10:30:00Z");

    @Mock
    private EmailReminderTaskMapper taskMapper;

    @Mock
    private MailPort mailPort;

    private EmailReminderScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new EmailReminderScheduler(
                taskMapper,
                mailPort,
                mock(BillingPort.class)
        );
        ReflectionTestUtils.setField(scheduler, "batchSize", 20);
        ReflectionTestUtils.setField(scheduler, "claimTimeoutMs", 600_000L);
        ReflectionTestUtils.setField(scheduler, "maxSendAttempts", 3);
        ReflectionTestUtils.setField(scheduler, "retryDelayMs", 30_000L);
        ReflectionTestUtils.setField(scheduler, "displayZone", "Asia/Shanghai");
    }

    @Test
    void dispatchLetsMysqlEvaluateDueTimeAndStateTimestamps() {
        EmailReminderTask task = new EmailReminderTask();
        task.setId(11L);
        task.setRecipientEmail("recipient@example.com");
        task.setSubject("Reminder");
        task.setMessage("Message");
        task.setFrequency("ONCE");
        task.setTimezone("Asia/Shanghai");
        task.setScheduledAt(NOW);

        when(taskMapper.selectDueTasks(30_000L, 20))
                .thenReturn(List.of(task));
        when(taskMapper.claimForSending(
                eq(11L),
                anyString()
        )).thenReturn(1);
        when(taskMapper.markSent(
                eq(11L),
                anyString()
        )).thenReturn(1);

        scheduler.dispatchDueReminders();

        verify(taskMapper).failStaleClaims(
                600_000L,
                "发送进程意外中断，任务已停止以避免重复发送，请手动恢复后重试"
        );
        verify(taskMapper).selectDueTasks(30_000L, 20);
        // contentType / templateId 都是 null、正文里没有标记 ⇒ 渲染结果是**纯文本**，
        // 所以走 sendSimpleMail 而不是 sendHtmlMail。
        verify(mailPort).sendSimpleMail(
                "recipient@example.com",
                "Reminder",
                "Message"
        );
    }

    @Test
    void transientFailureIsRequeuedBeforeAttemptLimit() {
        EmailReminderTask task = new EmailReminderTask();
        task.setId(12L);
        task.setRecipientEmail("recipient@example.com");
        task.setSubject("Reminder");
        task.setMessage("Message");
        task.setScheduledAt(NOW);
        task.setSendAttempts(0);

        when(taskMapper.selectDueTasks(30_000L, 20))
                .thenReturn(List.of(task));
        when(taskMapper.claimForSending(
                eq(12L),
                anyString()
        )).thenReturn(1);
        when(taskMapper.requeueAfterFailure(
                eq(12L),
                anyString(),
                eq("temporary SMTP failure")
        )).thenReturn(1);
        doThrow(new RuntimeException("temporary SMTP failure"))
                .when(mailPort)
                .sendSimpleMail(
                        "recipient@example.com",
                        "Reminder",
                        "Message"
                );

        scheduler.dispatchDueReminders();

        verify(taskMapper).requeueAfterFailure(
                eq(12L),
                anyString(),
                eq("temporary SMTP failure")
        );
    }

    /**
     * HTML 路径：{@code templateId} 选中一个版式 ⇒ 渲染成 HTML，走 {@code sendHtmlMail}。
     *
     * <p>⚠️ 这条用例是本刀之后<b>唯一</b>能证明"渲染发生在 automation 侧、而端口只收成品"的
     * 地方 —— 断言的是"两个正文都非空"，而不是"某个模板被调用了"。改造前这里断言的是
     * {@code sendReminderMail(..., NOW, null, "classic")}，即把渲染参数原样交给了邮件服务。</p>
     */
    @Test
    void htmlTemplateIsRenderedHereAndThePortReceivesFinishedBodies() {
        EmailReminderTask task = new EmailReminderTask();
        task.setId(13L);
        task.setRecipientEmail("recipient@example.com");
        task.setSubject("Reminder");
        task.setMessage("Message");
        task.setTemplateId("classic");
        task.setScheduledAt(NOW);

        when(taskMapper.selectDueTasks(30_000L, 20))
                .thenReturn(List.of(task));
        when(taskMapper.claimForSending(
                eq(13L),
                anyString()
        )).thenReturn(1);
        when(taskMapper.markSent(
                eq(13L),
                anyString()
        )).thenReturn(1);

        scheduler.dispatchDueReminders();

        ArgumentCaptor<String> plain = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(mailPort).sendHtmlMail(
                eq("recipient@example.com"),
                eq("Reminder"),
                plain.capture(),
                html.capture()
        );
        assertTrue(plain.getValue().contains("Message"), "纯文本兜底里要有正文");
        assertTrue(html.getValue().contains("Message"), "HTML 正文里要有正文");
        // 展示时区来自 email-reminder.display-zone，而不是任务自己的 timezone。
        assertTrue(html.getValue().contains("Asia/Shanghai"), "HTML 里要带展示时区");
        verify(mailPort, never()).sendSimpleMail(anyString(), anyString(), anyString());
    }
}
