package com.xander.lab.automation.infrastructure;

import com.xander.lab.automation.domain.EmailReminderTask;
import com.xander.lab.automation.infrastructure.EmailReminderTaskMapper;
import com.xander.lab.payment.api.BillingPort;
import com.xander.lab.integration.mail.MailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
                mock(BillingPort.class)
        );
        ReflectionTestUtils.setField(scheduler, "batchSize", 20);
        ReflectionTestUtils.setField(scheduler, "claimTimeoutMs", 600_000L);
        ReflectionTestUtils.setField(scheduler, "maxSendAttempts", 3);
        ReflectionTestUtils.setField(scheduler, "retryDelayMs", 30_000L);
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
        verify(mailService).sendReminderMail(
                "recipient@example.com",
                "Reminder",
                "Message",
                NOW,
                null,
                null
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
                .when(mailService)
                .sendReminderMail(
                        "recipient@example.com",
                        "Reminder",
                        "Message",
                        NOW,
                        null,
                        null
                );

        scheduler.dispatchDueReminders();

        verify(taskMapper).requeueAfterFailure(
                eq(12L),
                anyString(),
                eq("temporary SMTP failure")
        );
    }
}
