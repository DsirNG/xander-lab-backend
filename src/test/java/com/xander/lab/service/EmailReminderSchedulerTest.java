package com.xander.lab.service;

import com.xander.lab.entity.EmailReminderTask;
import com.xander.lab.mapper.EmailReminderTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailReminderSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-07-24T10:30:00Z");

    @Mock
    private EmailReminderTaskMapper taskMapper;

    @Mock
    private MailService mailService;

    private EmailReminderScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new EmailReminderScheduler(
                taskMapper,
                mailService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        ReflectionTestUtils.setField(scheduler, "batchSize", 20);
        ReflectionTestUtils.setField(scheduler, "claimTimeoutMs", 600_000L);
        ReflectionTestUtils.setField(scheduler, "maxSendAttempts", 3);
        ReflectionTestUtils.setField(scheduler, "retryDelayMs", 30_000L);
    }

    @Test
    void dispatchUsesUtcClockForDueQueryAndStateTransitions() {
        EmailReminderTask task = new EmailReminderTask();
        task.setId(11L);
        task.setRecipientEmail("recipient@example.com");
        task.setSubject("Reminder");
        task.setMessage("Message");
        task.setScheduledAt(NOW);

        when(taskMapper.selectDueTasks(NOW, NOW.minusSeconds(30), 20))
                .thenReturn(List.of(task));
        when(taskMapper.claimForSending(
                org.mockito.ArgumentMatchers.eq(11L),
                anyString(),
                org.mockito.ArgumentMatchers.eq(NOW)
        )).thenReturn(1);
        when(taskMapper.markSent(
                org.mockito.ArgumentMatchers.eq(11L),
                anyString(),
                org.mockito.ArgumentMatchers.eq(NOW)
        )).thenReturn(1);

        scheduler.dispatchDueReminders();

        verify(taskMapper).failStaleClaims(
                NOW.minusMillis(600_000L),
                NOW,
                "发送进程意外中断，任务已停止以避免重复发送，请手动恢复后重试"
        );
        verify(taskMapper).selectDueTasks(NOW, NOW.minusSeconds(30), 20);
        verify(mailService).sendReminderMail(
                "recipient@example.com",
                "Reminder",
                "Message",
                NOW
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

        when(taskMapper.selectDueTasks(NOW, NOW.minusSeconds(30), 20))
                .thenReturn(List.of(task));
        when(taskMapper.claimForSending(
                org.mockito.ArgumentMatchers.eq(12L),
                anyString(),
                org.mockito.ArgumentMatchers.eq(NOW)
        )).thenReturn(1);
        when(taskMapper.requeueAfterFailure(
                org.mockito.ArgumentMatchers.eq(12L),
                anyString(),
                org.mockito.ArgumentMatchers.eq("temporary SMTP failure"),
                org.mockito.ArgumentMatchers.eq(NOW)
        )).thenReturn(1);
        doThrow(new RuntimeException("temporary SMTP failure"))
                .when(mailService)
                .sendReminderMail(
                        "recipient@example.com",
                        "Reminder",
                        "Message",
                        NOW
                );

        scheduler.dispatchDueReminders();

        verify(taskMapper).requeueAfterFailure(
                org.mockito.ArgumentMatchers.eq(12L),
                anyString(),
                org.mockito.ArgumentMatchers.eq("temporary SMTP failure"),
                org.mockito.ArgumentMatchers.eq(NOW)
        );
    }
}
