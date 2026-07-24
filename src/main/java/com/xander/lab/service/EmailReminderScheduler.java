package com.xander.lab.service;

import com.xander.lab.entity.EmailReminderTask;
import com.xander.lab.mapper.EmailReminderTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Claims due reminders using an atomic database update before performing SMTP
 * I/O. This keeps network calls outside transactions and prevents two service
 * instances from sending the same pending row concurrently.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "email-reminder.enabled", havingValue = "true")
public class EmailReminderScheduler {

    private static final String STALE_CLAIM_ERROR =
            "发送进程意外中断，任务已停止以避免重复发送，请手动恢复后重试";

    private final EmailReminderTaskMapper taskMapper;
    private final MailService mailService;
    private final Clock clock;

    @Value("${email-reminder.batch-size:20}")
    private int batchSize;

    @Value("${email-reminder.claim-timeout-ms:600000}")
    private long claimTimeoutMs;

    @Value("${email-reminder.max-send-attempts:3}")
    private int maxSendAttempts;

    @Value("${email-reminder.retry-delay-ms:30000}")
    private long retryDelayMs;

    @Scheduled(
            fixedDelayString = "${email-reminder.poll-interval-ms:10000}",
            initialDelayString = "${email-reminder.initial-delay-ms:15000}"
    )
    public void dispatchDueReminders() {
        Instant now = clock.instant();
        try {
            int recovered = taskMapper.failStaleClaims(
                    now.minusMillis(claimTimeoutMs),
                    now,
                    STALE_CLAIM_ERROR
            );
            if (recovered > 0) {
                log.warn("[EmailReminder] 已将 {} 个超时发送任务标记为失败", recovered);
            }

            List<EmailReminderTask> dueTasks = taskMapper.selectDueTasks(
                    now,
                    now.minusMillis(Math.max(1, retryDelayMs)),
                    Math.max(1, batchSize)
            );
            for (EmailReminderTask task : dueTasks) {
                dispatchOne(task);
            }
        } catch (Exception e) {
            log.error("[EmailReminder] 调度轮询失败", e);
        }
    }

    private void dispatchOne(EmailReminderTask task) {
        Instant claimedAt = clock.instant();
        String processingToken = UUID.randomUUID().toString();
        if (taskMapper.claimForSending(task.getId(), processingToken, claimedAt) != 1) {
            return;
        }

        try {
            // SMTP is deliberately performed after the atomic claim and without
            // a surrounding database transaction.
            mailService.sendReminderMail(
                    task.getRecipientEmail(),
                    task.getSubject(),
                    task.getMessage(),
                    task.getScheduledAt()
            );
            if (taskMapper.markSent(task.getId(), processingToken, clock.instant()) != 1) {
                log.error("[EmailReminder] 邮件已发送但任务完成状态写入失败：taskId={}", task.getId());
            }
        } catch (Exception e) {
            String errorMessage = limitError(rootMessage(e));
            int attempt = (task.getSendAttempts() == null ? 0 : task.getSendAttempts()) + 1;
            boolean shouldRetry = attempt < Math.max(1, maxSendAttempts);
            Instant failedAt = clock.instant();
            int updated = shouldRetry
                    ? taskMapper.requeueAfterFailure(
                            task.getId(),
                            processingToken,
                            errorMessage,
                            failedAt
                    )
                    : taskMapper.markFailed(
                            task.getId(),
                            processingToken,
                            errorMessage,
                            failedAt
                    );
            if (updated != 1) {
                log.error("[EmailReminder] 发送失败且任务错误状态写入失败：taskId={}, reason={}",
                        task.getId(), errorMessage);
            } else if (shouldRetry) {
                log.warn("[EmailReminder] 第 {} 次发送失败，将自动重试：taskId={}, reason={}",
                        attempt, task.getId(), errorMessage);
            } else {
                log.warn("[EmailReminder] 发送失败：taskId={}, reason={}", task.getId(), errorMessage);
            }
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? "邮件发送失败" : message;
    }

    private String limitError(String value) {
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
