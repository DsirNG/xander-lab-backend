package com.xander.lab.automation.infrastructure;

import com.xander.lab.automation.domain.EmailReminderMailRenderer;
import com.xander.lab.automation.domain.EmailReminderScheduleCalculator;
import com.xander.lab.payment.api.BillingBiz;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Content-owned email reminder executor.
 *
 * <p>Rows are claimed atomically before SMTP I/O. Mail delivery and billing cross the Core
 * boundary only after the claim, and the old Core scheduler remains independently gated during
 * the drain window.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "content-automation.email-reminder-scheduler", name = "enabled",
        havingValue = "true")
public class ContentEmailReminderScheduler {

    private static final String STALE_CLAIM_ERROR =
            "发送进程意外中断，任务已停止以避免重复发送，请手动恢复后重试";

    private final JdbcTemplate jdbcTemplate;
    private final ContentMailClient mailClient;
    private final ContentBillingClient billingClient;
    /** Required so enabling this scheduler without ShedLock fails closed at startup. */
    @SuppressWarnings("unused")
    private final LockProvider lockProvider;

    @Value("${content-automation.email-reminder-scheduler.display-zone:Asia/Shanghai}")
    private String displayZone;

    @Value("${content-automation.email-reminder-scheduler.batch-size:20}")
    private int batchSize;

    @Value("${content-automation.email-reminder-scheduler.claim-timeout-ms:600000}")
    private long claimTimeoutMs;

    @Value("${content-automation.email-reminder-scheduler.max-send-attempts:3}")
    private int maxSendAttempts;

    @Value("${content-automation.email-reminder-scheduler.retry-delay-ms:30000}")
    private long retryDelayMs;

    @PostConstruct
    void logEnabled() {
        log.info("[EmailReminder] Content 调度器已启用，ShedLock 全局锁配置有效");
    }

    @Scheduled(
            fixedDelayString = "${content-automation.email-reminder-scheduler.poll-interval-ms:10000}",
            initialDelayString = "${content-automation.email-reminder-scheduler.initial-delay-ms:15000}"
    )
    @SchedulerLock(name = "content-email-reminder-scheduler", lockAtMostFor = "PT2M")
    public void dispatchDueReminders() {
        try {
            int recovered = jdbcTemplate.update("""
                    UPDATE email_reminder_task
                    SET status = 'FAILED', processing_token = NULL, claimed_at = NULL,
                        error_message = ?
                    WHERE status = 'SENDING'
                      AND deleted_at IS NULL
                      AND claimed_at < TIMESTAMPADD(
                          MICROSECOND, -(?) * 1000, CURRENT_TIMESTAMP(3))
                    """, STALE_CLAIM_ERROR, Math.max(1, claimTimeoutMs));
            if (recovered > 0) {
                log.warn("[EmailReminder] 已将 {} 个超时发送任务标记为失败", recovered);
            }

            for (ReminderTask task : selectDueTasks()) {
                dispatchOne(task);
            }
        } catch (Exception exception) {
            log.error("[EmailReminder] content 调度轮询失败", exception);
        }
    }

    private List<ReminderTask> selectDueTasks() {
        return jdbcTemplate.query("""
                SELECT id, user_id, recipient_email, subject, message, content_type, template_id,
                       frequency, timezone, send_time, recurrence_day, interval_days,
                       scheduled_at, send_attempts
                FROM email_reminder_task
                WHERE status = 'PENDING'
                  AND deleted_at IS NULL
                  AND scheduled_at <= CURRENT_TIMESTAMP(3)
                  AND (send_attempts = 0 OR updated_at <= TIMESTAMPADD(
                      MICROSECOND, -(?) * 1000, CURRENT_TIMESTAMP(3)))
                ORDER BY scheduled_at ASC, id ASC
                LIMIT ?
                """, this::mapTask, Math.max(1, retryDelayMs), Math.max(1, batchSize));
    }

    private void dispatchOne(ReminderTask task) {
        String processingToken = UUID.randomUUID().toString();
        if (jdbcTemplate.update("""
                UPDATE email_reminder_task
                SET status = 'SENDING', processing_token = ?, claimed_at = CURRENT_TIMESTAMP(3),
                    send_attempts = send_attempts + 1, error_message = NULL
                WHERE id = ? AND deleted_at IS NULL AND status = 'PENDING'
                  AND scheduled_at <= CURRENT_TIMESTAMP(3)
                """, processingToken, task.id()) != 1) {
            return;
        }

        try {
            EmailReminderMailRenderer.Rendered rendered = EmailReminderMailRenderer.render(
                    task.subject(), task.message(), task.contentType(), task.templateId(),
                    task.scheduledAt(), displayZone);
            if (rendered.html()) {
                mailClient.sendHtmlMail(task.recipientEmail(), task.subject(), rendered.plainText(),
                        rendered.htmlBody());
            } else {
                mailClient.sendSimpleMail(task.recipientEmail(), task.subject(), rendered.plainText());
            }

            Instant nextScheduledAt = null;
            String frequency = task.frequency() == null
                    ? EmailReminderScheduleCalculator.FREQ_ONCE : task.frequency();
            if (!EmailReminderScheduleCalculator.FREQ_ONCE.equals(
                    EmailReminderScheduleCalculator.normalizeFrequency(frequency))) {
                nextScheduledAt = EmailReminderScheduleCalculator.resolveNextAfterSend(
                        frequency,
                        EmailReminderScheduleCalculator.resolveZone(task.timezone()),
                        task.sendTime(), task.recurrenceDay(), task.intervalDays(),
                        task.scheduledAt(), Instant.now());
            }

            int completed = nextScheduledAt == null
                    ? markSent(task.id(), processingToken)
                    : markSentAndReschedule(task.id(), processingToken, nextScheduledAt);
            if (completed != 1) {
                log.error("[EmailReminder] 邮件已发送但任务完成状态写入失败：taskId={}", task.id());
            } else {
                long points = billingClient.flatPoints(BillingBiz.EMAIL_REMINDER);
                billingClient.chargeFlat(task.userId(), BillingBiz.EMAIL_REMINDER,
                        "email:" + task.id() + ":" + task.scheduledAt().toEpochMilli(),
                        "task:" + task.id(), points, "定时邮件提醒发送成功");
            }
        } catch (Exception exception) {
            String errorMessage = limitError(rootMessage(exception));
            int attempt = (task.sendAttempts() == null ? 0 : task.sendAttempts()) + 1;
            boolean shouldRetry = attempt < Math.max(1, maxSendAttempts);
            int updated = shouldRetry ? requeueAfterFailure(task.id(), processingToken, errorMessage)
                    : markFailed(task.id(), processingToken, errorMessage);
            if (updated != 1) {
                log.error("[EmailReminder] 发送失败且任务错误状态写入失败：taskId={}, reason={}",
                        task.id(), errorMessage);
            } else if (shouldRetry) {
                log.warn("[EmailReminder] 第 {} 次发送失败，将自动重试：taskId={}, reason={}",
                        attempt, task.id(), errorMessage);
            } else {
                log.warn("[EmailReminder] 发送失败：taskId={}, reason={}", task.id(), errorMessage);
            }
        }
    }

    private int markSent(Long id, String processingToken) {
        return jdbcTemplate.update("""
                UPDATE email_reminder_task
                SET status = 'SENT', sent_at = CURRENT_TIMESTAMP(3), processing_token = NULL,
                    claimed_at = NULL, error_message = NULL
                WHERE id = ? AND deleted_at IS NULL AND status = 'SENDING'
                  AND processing_token = ?
                """, id, processingToken);
    }

    private int markSentAndReschedule(Long id, String processingToken, Instant nextScheduledAt) {
        return jdbcTemplate.update("""
                UPDATE email_reminder_task
                SET status = 'PENDING', scheduled_at = ?, sent_at = CURRENT_TIMESTAMP(3),
                    send_attempts = 0, processing_token = NULL, claimed_at = NULL,
                    error_message = NULL
                WHERE id = ? AND deleted_at IS NULL AND status = 'SENDING'
                  AND processing_token = ?
                """, Timestamp.from(nextScheduledAt), id, processingToken);
    }

    private int markFailed(Long id, String processingToken, String errorMessage) {
        return jdbcTemplate.update("""
                UPDATE email_reminder_task
                SET status = 'FAILED', processing_token = NULL, claimed_at = NULL,
                    error_message = ?
                WHERE id = ? AND deleted_at IS NULL AND status = 'SENDING'
                  AND processing_token = ?
                """, errorMessage, id, processingToken);
    }

    private int requeueAfterFailure(Long id, String processingToken, String errorMessage) {
        return jdbcTemplate.update("""
                UPDATE email_reminder_task
                SET status = 'PENDING', processing_token = NULL, claimed_at = NULL,
                    error_message = ?
                WHERE id = ? AND deleted_at IS NULL AND status = 'SENDING'
                  AND processing_token = ?
                """, errorMessage, id, processingToken);
    }

    private ReminderTask mapTask(ResultSet resultSet, int rowNum) throws SQLException {
        return new ReminderTask(
                resultSet.getLong("id"),
                resultSet.getLong("user_id"),
                resultSet.getString("recipient_email"),
                resultSet.getString("subject"),
                resultSet.getString("message"),
                resultSet.getString("content_type"),
                resultSet.getString("template_id"),
                resultSet.getString("frequency"),
                resultSet.getString("timezone"),
                resultSet.getString("send_time"),
                (Integer) resultSet.getObject("recurrence_day"),
                (Integer) resultSet.getObject("interval_days"),
                resultSet.getTimestamp("scheduled_at").toInstant(),
                (Integer) resultSet.getObject("send_attempts"));
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

    private record ReminderTask(Long id, Long userId, String recipientEmail, String subject,
                                String message, String contentType, String templateId,
                                String frequency, String timezone, String sendTime,
                                Integer recurrenceDay, Integer intervalDays, Instant scheduledAt,
                                Integer sendAttempts) {
    }
}
