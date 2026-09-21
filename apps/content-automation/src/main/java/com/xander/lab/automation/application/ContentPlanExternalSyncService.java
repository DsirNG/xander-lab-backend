package com.xander.lab.automation.application;

import com.xander.lab.automation.infrastructure.ContentNotificationStreamService;
import com.xander.lab.automation.infrastructure.PlatformBlogSyncClient;
import com.xander.lab.integration.api.BlogSyncResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Coordinates best-effort external publication after a local blog post is committed.
 *
 * <p>CSDN and Juejin attempts are deliberately independent. The local run remains successful
 * when one vendor fails; each attempt's status, external id, URL, and sanitized error are stored
 * on the run row, and the user receives a durable notification for a failed vendor.</p>
 */
@Service
public class ContentPlanExternalSyncService {

    private final JdbcTemplate jdbcTemplate;
    private final PlatformBlogSyncClient platformBlogSyncClient;
    private final ContentNotificationStreamService notificationStreamService;

    public ContentPlanExternalSyncService(JdbcTemplate jdbcTemplate,
                                          PlatformBlogSyncClient platformBlogSyncClient,
                                          ContentNotificationStreamService notificationStreamService) {
        this.jdbcTemplate = jdbcTemplate;
        this.platformBlogSyncClient = platformBlogSyncClient;
        this.notificationStreamService = notificationStreamService;
    }

    /** Executes the selected platform attempts outside the caller's database transaction. */
    public void sync(ExternalSyncPlan plan) {
        if (plan.syncCsdn()) {
            syncCsdn(plan);
        }
        if (plan.syncJuejin()) {
            syncJuejin(plan);
        }
    }

    private void syncCsdn(ExternalSyncPlan plan) {
        try {
            BlogSyncResult result = platformBlogSyncClient.syncCsdn(plan.userId(), plan.postId());
            jdbcTemplate.update("""
                    UPDATE blog_plan_run
                    SET csdn_status = 'SUCCEEDED', csdn_error_code = NULL,
                        csdn_error_message = NULL, csdn_external_id = ?, csdn_url = ?
                    WHERE id = ?
                    """, result.externalId(), result.url(), plan.runId());
        } catch (RuntimeException exception) {
            recordCsdnFailure(plan, rootMessage(exception));
        }
    }

    private void syncJuejin(ExternalSyncPlan plan) {
        try {
            BlogSyncResult result = platformBlogSyncClient.syncJuejin(plan.userId(), plan.postId());
            jdbcTemplate.update("""
                    UPDATE blog_plan_run
                    SET juejin_status = 'SUCCEEDED', juejin_error_code = NULL,
                        juejin_error_message = NULL, juejin_external_id = ?, juejin_url = ?
                    WHERE id = ?
                    """, result.externalId(), result.url(), plan.runId());
        } catch (RuntimeException exception) {
            recordJuejinFailure(plan, rootMessage(exception));
        }
    }

    private void recordCsdnFailure(ExternalSyncPlan plan, String message) {
        String safeMessage = limit(message, 1000);
        jdbcTemplate.update("""
                UPDATE blog_plan_run
                SET csdn_status = 'FAILED', csdn_error_code = 'PUBLISH_FAILED',
                    csdn_error_message = ?
                WHERE id = ?
                """, safeMessage, plan.runId());
        notifyUser(plan, "CSDN_FAILED", "CSDN 同步失败",
                "计划《" + plan.topic() + "》已在本站发布，但同步 CSDN 失败：" + safeMessage);
    }

    private void recordJuejinFailure(ExternalSyncPlan plan, String message) {
        String safeMessage = limit(message, 1000);
        jdbcTemplate.update("""
                UPDATE blog_plan_run
                SET juejin_status = 'FAILED', juejin_error_code = 'PUBLISH_FAILED',
                    juejin_error_message = ?
                WHERE id = ?
                """, safeMessage, plan.runId());
        notifyUser(plan, "JUEJIN_FAILED", "掘金同步失败",
                "计划《" + plan.topic() + "》已在本站发布，但同步掘金失败：" + safeMessage);
    }

    private void notifyUser(ExternalSyncPlan plan, String type, String title, String message) {
        if (plan.userId() == null || plan.userId() <= 0) {
            return;
        }
        String safeMessage = limit(message, 1000);
        jdbcTemplate.update("INSERT INTO blog_notification "
                        + "(user_id, type, title, message, plan_id, run_id, is_read) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0)",
                plan.userId(), type, title, safeMessage, plan.planId(), plan.runId());
        notificationStreamService.push(plan.userId(), type, title, safeMessage,
                plan.planId(), plan.runId());
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return "未知站外同步错误";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /** Immutable context for one local run's independent platform attempts. */
    public record ExternalSyncPlan(Long runId, Long userId, Long planId, String topic,
                                   Long postId, boolean syncCsdn, boolean syncJuejin) {
    }
}
