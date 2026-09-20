package com.xander.lab.automation.application;

import com.xander.lab.automation.api.EmailReminderListResult;
import com.xander.lab.automation.api.EmailReminderStatsVO;
import com.xander.lab.automation.api.EmailReminderTaskVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class EmailReminderReadService {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final Set<Integer> ALLOWED_PAGE_SIZES = Set.of(5, 10, 15, 20);
    private static final Set<String> LIST_STATUSES = Set.of(
            "PENDING", "PAUSED", "SENDING", "SENT", "FAILED");

    private final JdbcTemplate jdbcTemplate;

    @Value("${mail.from:}")
    private String senderEmail;

    public EmailReminderListResult list(
            Long userId,
            int page,
            int size,
            String status,
            String search) {
        requireUser(userId);

        int safePage = Math.max(page, 1);
        int safeSize = ALLOWED_PAGE_SIZES.contains(size) ? size : DEFAULT_PAGE_SIZE;
        String statusFilter = normalizeListStatus(status);
        String keyword = search == null ? "" : search.strip();
        List<Object> filters = new ArrayList<>();
        StringBuilder where = new StringBuilder(
                " FROM email_reminder_task WHERE user_id = ? AND deleted_at IS NULL");
        filters.add(userId);
        if (statusFilter != null) {
            where.append(" AND status = ?");
            filters.add(statusFilter);
        }
        if (!keyword.isEmpty()) {
            where.append(" AND (subject LIKE ? OR recipient_email LIKE ?)");
            String pattern = "%" + keyword + "%";
            filters.add(pattern);
            filters.add(pattern);
        }

        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*)" + where, Long.class, filters.toArray());
        long offset = (long) (safePage - 1) * safeSize;
        List<Object> rowParameters = new ArrayList<>(filters);
        rowParameters.add(safeSize);
        rowParameters.add(offset);
        List<EmailReminderTaskVO> records = jdbcTemplate.query(
                "SELECT id, client_request_id, recipient_email, subject, message, content_type, "
                        + "template_id, frequency, timezone, send_time, recurrence_day, interval_days, "
                        + "scheduled_at, status, send_attempts, error_message, sent_at, created_at, updated_at"
                        + where
                        + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                this::mapTask,
                rowParameters.toArray());

        long pages = total == 0 ? 0 : (total + safeSize - 1) / safeSize;
        return EmailReminderListResult.builder()
                .records(records)
                .total(total)
                .current(safePage)
                .pages(pages)
                .size(safeSize)
                .hasMore(safePage < pages)
                .stats(buildStats(userId))
                .build();
    }

    public EmailReminderTaskVO findOwned(Long userId, Long taskId) {
        requireUser(userId);
        List<EmailReminderTaskVO> records = jdbcTemplate.query(
                "SELECT id, client_request_id, recipient_email, subject, message, content_type, "
                        + "template_id, frequency, timezone, send_time, recurrence_day, interval_days, "
                        + "scheduled_at, status, send_attempts, error_message, sent_at, created_at, updated_at "
                        + "FROM email_reminder_task WHERE id = ? AND user_id = ? AND deleted_at IS NULL",
                this::mapTask,
                taskId,
                userId);
        return records.isEmpty() ? null : records.get(0);
    }

    private EmailReminderStatsVO buildStats(Long userId) {
        Long total = count(userId, null);
        Long pending = count(userId, "PENDING");
        Long sending = count(userId, "SENDING");
        Long sent = count(userId, "SENT");
        return EmailReminderStatsVO.builder()
                .total(total)
                .active(pending + sending)
                .sent(sent)
                .pending(pending)
                .build();
    }

    private Long count(Long userId, String status) {
        if (status == null) {
            return jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM email_reminder_task "
                            + "WHERE user_id = ? AND deleted_at IS NULL",
                    Long.class, userId);
        }
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_reminder_task "
                        + "WHERE user_id = ? AND deleted_at IS NULL AND status = ?",
                Long.class, userId, status);
    }

    private String normalizeListStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.strip().toUpperCase(Locale.ROOT);
        if ("ALL".equals(normalized) || "SCHEDULED".equals(normalized)
                || "ACTIVE".equals(normalized)) {
            return "ALL".equals(normalized) ? null : "PENDING";
        }
        if ("PROCESSING".equals(normalized)) {
            return "SENDING";
        }
        if (!LIST_STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的任务状态筛选");
        }
        return normalized;
    }

    private EmailReminderTaskVO mapTask(ResultSet rs, int rowNum) throws SQLException {
        return EmailReminderTaskVO.builder()
                .id(rs.getLong("id"))
                .clientRequestId(rs.getString("client_request_id"))
                .senderEmail(senderEmail)
                .recipientEmail(rs.getString("recipient_email"))
                .subject(rs.getString("subject"))
                .message(rs.getString("message"))
                .contentType(rs.getString("content_type"))
                .templateId(rs.getString("template_id"))
                .frequency(defaultValue(rs.getString("frequency"), "ONCE"))
                .timezone(defaultValue(rs.getString("timezone"), "Asia/Shanghai"))
                .sendTime(rs.getString("send_time"))
                .recurrenceDay(toInteger(rs, "recurrence_day"))
                .intervalDays(toInteger(rs, "interval_days"))
                .scheduledAt(toInstant(rs, "scheduled_at"))
                .status(rs.getString("status"))
                .sendAttempts(toInteger(rs, "send_attempts"))
                .errorMessage(rs.getString("error_message"))
                .sentAt(toInstant(rs, "sent_at"))
                .createdAt(toInstant(rs, "created_at"))
                .updatedAt(toInstant(rs, "updated_at"))
                .build();
    }

    private Instant toInstant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Integer toInteger(ResultSet rs, String column) throws SQLException {
        Number value = (Number) rs.getObject(column);
        return value == null ? null : value.intValue();
    }

    private String defaultValue(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private void requireUser(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
    }
}
