package com.xander.lab.automation.application;

import com.xander.lab.automation.api.EmailReminderCreateRequest;
import com.xander.lab.automation.api.EmailReminderTaskVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class EmailReminderWriteService {

    private static final String UNAUTHORIZED_MESSAGE = "未登录或登录已过期";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_PAUSED = "PAUSED";
    private static final String STATUS_SENDING = "SENDING";
    private static final String STATUS_FAILED = "FAILED";
    private static final String FREQ_ONCE = "ONCE";
    private static final String FREQ_DAILY = "DAILY";
    private static final String FREQ_WEEKLY = "WEEKLY";
    private static final String FREQ_MONTHLY = "MONTHLY";
    private static final String FREQ_CUSTOM = "CUSTOM";
    private static final String CONTENT_PLAIN = "PLAIN";
    private static final String CONTENT_HTML = "HTML";
    private static final Set<String> FREQUENCIES = Set.of(
            FREQ_ONCE, FREQ_DAILY, FREQ_WEEKLY, FREQ_MONTHLY, FREQ_CUSTOM);
    private static final Set<String> TEMPLATES = Set.of("classic", "minimal", "card", "notice", "none");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final JdbcTemplate jdbcTemplate;
    private final EmailReminderReadService readService;

    @Value("${email-reminder.minimum-lead-seconds:30}")
    private long minimumLeadSeconds;

    @Value("${email-reminder.max-active-per-user:50}")
    private long maxActivePerUser;

    @Value("${email-reminder.max-created-per-day:20}")
    private long maxCreatedPerDay;

    @Transactional
    public EmailReminderTaskVO create(Long userId, EmailReminderCreateRequest request) {
        requireUser(userId);
        lockUser(userId);

        String clientRequestId = normalizeClientRequestId(request.getClientRequestId());
        if (clientRequestId != null) {
            Long existingId = findTaskIdByClientRequestId(userId, clientRequestId);
            if (existingId != null) {
                EmailReminderTaskVO existing = readService.findOwned(userId, existingId);
                if (existing == null) {
                    throw conflict("该幂等请求标识已被删除任务使用，请生成新的标识");
                }
                return existing;
            }
        }

        String frequency = normalizeFrequency(request.getFrequency());
        ZoneId zone = resolveZone(request.getTimezone());
        Instant now = Instant.now();
        Instant onceAt = request.getScheduledAt() == null
                ? null : request.getScheduledAt().toInstant();
        Instant scheduledAt = resolveFirstFire(
                frequency, zone, onceAt, request.getSendTime(),
                request.getRecurrenceDay(), request.getIntervalDays(), now);
        if (!scheduledAt.isAfter(now.plusSeconds(Math.max(1, minimumLeadSeconds)))) {
            throw new IllegalArgumentException(
                    "发送时间至少需要晚于当前时间 " + Math.max(1, minimumLeadSeconds) + " 秒");
        }
        if (countActiveTasks(userId) >= maxActivePerUser) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "进行中的定时邮件任务已达到上限，请先完成或删除部分任务");
        }
        if (countCreatedInPastDay(userId) >= maxCreatedPerDay) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "过去 24 小时创建的定时邮件已达到上限");
        }

        String subject = request.getSubject().strip();
        if (subject.contains("\r") || subject.contains("\n")) {
            throw new IllegalArgumentException("邮件主题不能包含换行符");
        }
        String sql = "INSERT INTO email_reminder_task "
                + "(user_id, client_request_id, recipient_email, subject, message, content_type, "
                + "template_id, frequency, timezone, send_time, recurrence_day, interval_days, "
                + "scheduled_at, status, send_attempts) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            if (clientRequestId == null) statement.setNull(2, java.sql.Types.VARCHAR);
            else statement.setString(2, clientRequestId);
            statement.setString(3, request.getRecipientEmail().strip().toLowerCase(Locale.ROOT));
            statement.setString(4, subject);
            statement.setString(5, request.getMessage().strip());
            statement.setString(6, normalizeContentType(request.getContentType()));
            statement.setString(7, normalizeTemplateId(request.getTemplateId()));
            statement.setString(8, frequency);
            statement.setString(9, zone.getId());
            String sendTime = FREQ_ONCE.equals(frequency) ? null : parseSendTime(request.getSendTime()).format(TIME_FORMAT);
            if (sendTime == null) statement.setNull(10, java.sql.Types.VARCHAR);
            else statement.setString(10, sendTime);
            setNullableInt(statement, 11, normalizeRecurrenceDay(frequency, request.getRecurrenceDay()));
            setNullableInt(statement, 12, normalizeIntervalDays(frequency, request.getIntervalDays()));
            statement.setTimestamp(13, Timestamp.from(scheduledAt));
            statement.setString(14, STATUS_PENDING);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("定时邮件任务创建后无法读取");
        }
        EmailReminderTaskVO created = readService.findOwned(userId, key.longValue());
        if (created == null) {
            throw new IllegalStateException("定时邮件任务创建后无法读取");
        }
        return created;
    }

    @Transactional
    public EmailReminderTaskVO updateStatus(Long userId, Long taskId, String requestedStatus) {
        requireUser(userId);
        EmailReminderTaskVO task = requireOwnedTask(userId, taskId);
        String targetStatus = requestedStatus.toUpperCase(Locale.ROOT);
        if (targetStatus.equals(task.getStatus())) {
            return task;
        }

        int updated;
        if (STATUS_PAUSED.equals(targetStatus)) {
            if (!STATUS_PENDING.equals(task.getStatus())) {
                throw conflict("当前状态无法暂停");
            }
            updated = jdbcTemplate.update(
                    "UPDATE email_reminder_task SET status = 'PAUSED' "
                            + "WHERE id = ? AND user_id = ? AND deleted_at IS NULL AND status = 'PENDING'",
                    taskId, userId);
        } else if (STATUS_PENDING.equals(targetStatus)) {
            if (!STATUS_PAUSED.equals(task.getStatus()) && !STATUS_FAILED.equals(task.getStatus())) {
                throw conflict("当前状态无法恢复");
            }
            Instant now = Instant.now();
            Instant nextAt = task.getScheduledAt();
            String frequency = normalizeFrequency(task.getFrequency());
            if (nextAt == null || !nextAt.isAfter(now)) {
                if (FREQ_ONCE.equals(frequency)) {
                    throw new IllegalArgumentException("计划发送时间已过，请删除后重新创建任务");
                }
                nextAt = resolveNextAfterSend(
                        frequency,
                        resolveZone(task.getTimezone()),
                        task.getSendTime(),
                        task.getRecurrenceDay(),
                        task.getIntervalDays(),
                        task.getScheduledAt(),
                        now);
            }
            updated = jdbcTemplate.update(
                    "UPDATE email_reminder_task SET status = 'PENDING', scheduled_at = ?, "
                            + "error_message = NULL, processing_token = NULL, claimed_at = NULL "
                            + "WHERE id = ? AND user_id = ? AND deleted_at IS NULL "
                            + "AND status IN ('PAUSED', 'FAILED')",
                    Timestamp.from(nextAt), taskId, userId);
        } else {
            throw new IllegalArgumentException("任务状态只支持 PENDING 或 PAUSED");
        }
        if (updated != 1) {
            throw conflict("任务状态已变化，请刷新后重试");
        }
        return requireOwnedTask(userId, taskId);
    }

    @Transactional
    public void delete(Long userId, Long taskId) {
        requireUser(userId);
        EmailReminderTaskVO task = requireOwnedTask(userId, taskId);
        if (STATUS_SENDING.equals(task.getStatus())) {
            throw conflict("邮件正在发送，暂时不能删除");
        }
        if (jdbcTemplate.update(
                "UPDATE email_reminder_task SET deleted_at = CURRENT_TIMESTAMP(3) "
                        + "WHERE id = ? AND user_id = ? AND deleted_at IS NULL AND status <> 'SENDING'",
                taskId, userId) != 1) {
            throw conflict("任务状态已变化，请刷新后重试");
        }
    }

    private EmailReminderTaskVO requireOwnedTask(Long userId, Long taskId) {
        EmailReminderTaskVO task = readService.findOwned(userId, taskId);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "定时邮件任务不存在");
        }
        return task;
    }

    private void lockUser(Long userId) {
        Long lockedUserId = jdbcTemplate.query(
                "SELECT id FROM sys_user WHERE id = ? FOR UPDATE",
                rs -> rs.next() ? rs.getLong(1) : null,
                userId);
        if (lockedUserId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, UNAUTHORIZED_MESSAGE);
        }
    }

    private Long findTaskIdByClientRequestId(Long userId, String clientRequestId) {
        return jdbcTemplate.query(
                "SELECT id FROM email_reminder_task WHERE user_id = ? AND client_request_id = ?",
                rs -> rs.next() ? rs.getLong(1) : null,
                userId, clientRequestId);
    }

    private long countActiveTasks(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_reminder_task WHERE user_id = ? "
                        + "AND deleted_at IS NULL AND status IN ('PENDING', 'PAUSED', 'SENDING')",
                Long.class, userId);
    }

    private long countCreatedInPastDay(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_reminder_task WHERE user_id = ? "
                        + "AND created_at >= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY)",
                Long.class, userId);
    }

    private String normalizeFrequency(String frequency) {
        String normalized = frequency == null || frequency.isBlank()
                ? FREQ_ONCE : frequency.strip().toUpperCase(Locale.ROOT);
        if (!FREQUENCIES.contains(normalized)) {
            throw new IllegalArgumentException("发送频率只支持 ONCE / DAILY / WEEKLY / MONTHLY / CUSTOM");
        }
        return normalized;
    }

    private ZoneId resolveZone(String timezone) {
        String value = timezone == null || timezone.isBlank() ? "Asia/Shanghai" : timezone.strip();
        try {
            return ZoneId.of(value);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("时区无效：" + value);
        }
    }

    private LocalTime parseSendTime(String sendTime) {
        if (sendTime == null || sendTime.isBlank()) {
            throw new IllegalArgumentException("请设置发送时间（HH:mm）");
        }
        try {
            return LocalTime.parse(sendTime.strip(), TIME_FORMAT);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("发送时间格式应为 HH:mm");
        }
    }

    private Instant resolveFirstFire(String frequency, ZoneId zone, Instant onceAt,
                                     String sendTime, Integer recurrenceDay, Integer intervalDays,
                                     Instant now) {
        if (FREQ_ONCE.equals(frequency)) {
            if (onceAt == null || !onceAt.isAfter(now)) {
                throw new IllegalArgumentException("发送时间必须晚于当前时间");
            }
            return onceAt;
        }
        LocalTime time = parseSendTime(sendTime);
        return switch (frequency) {
            case FREQ_DAILY, FREQ_CUSTOM -> nextDaily(zone, time, now);
            case FREQ_WEEKLY -> nextWeekly(zone, time, requireWeekday(recurrenceDay), now);
            case FREQ_MONTHLY -> nextMonthly(zone, time, requireMonthDay(recurrenceDay), now);
            default -> throw new IllegalArgumentException("不支持的发送频率");
        };
    }

    private Instant resolveNextAfterSend(String frequency, ZoneId zone, String sendTime,
                                         Integer recurrenceDay, Integer intervalDays,
                                         Instant previousFire, Instant now) {
        LocalTime time = parseSendTime(sendTime);
        Instant anchor = previousFire == null ? now : previousFire;
        return switch (frequency) {
            case FREQ_DAILY -> nextDaily(zone, time, anchor.plusSeconds(1));
            case FREQ_WEEKLY -> nextWeekly(zone, time, requireWeekday(recurrenceDay), anchor.plusSeconds(1));
            case FREQ_MONTHLY -> nextMonthly(zone, time, requireMonthDay(recurrenceDay), anchor.plusSeconds(1));
            case FREQ_CUSTOM -> {
                int days = requireIntervalDays(intervalDays);
                ZonedDateTime next = previousFire.atZone(zone).plusDays(days)
                        .withHour(time.getHour()).withMinute(time.getMinute())
                        .withSecond(0).withNano(0);
                while (!next.toInstant().isAfter(now)) next = next.plusDays(days);
                yield next.toInstant();
            }
            default -> throw new IllegalArgumentException("不支持的发送频率");
        };
    }

    private Instant nextDaily(ZoneId zone, LocalTime time, Instant after) {
        ZonedDateTime local = after.atZone(zone);
        ZonedDateTime candidate = ZonedDateTime.of(local.toLocalDate(), time, zone);
        return (!candidate.toInstant().isAfter(after) ? candidate.plusDays(1) : candidate).toInstant();
    }

    private Instant nextWeekly(ZoneId zone, LocalTime time, int weekday, Instant after) {
        ZonedDateTime local = after.atZone(zone);
        LocalDate date = local.toLocalDate();
        int delta = weekday - date.getDayOfWeek().getValue();
        if (delta < 0) delta += 7;
        ZonedDateTime candidate = ZonedDateTime.of(date.plusDays(delta), time, zone);
        return (!candidate.toInstant().isAfter(after) ? candidate.plusWeeks(1) : candidate).toInstant();
    }

    private Instant nextMonthly(ZoneId zone, LocalTime time, int day, Instant after) {
        ZonedDateTime local = after.atZone(zone);
        LocalDate month = local.toLocalDate().withDayOfMonth(1);
        ZonedDateTime candidate = atMonthDay(month, day, time, zone);
        if (!candidate.toInstant().isAfter(after)) candidate = atMonthDay(month.plusMonths(1), day, time, zone);
        return candidate.toInstant();
    }

    private ZonedDateTime atMonthDay(LocalDate month, int day, LocalTime time, ZoneId zone) {
        return ZonedDateTime.of(month.withDayOfMonth(Math.min(day, month.lengthOfMonth())), time, zone);
    }

    private int requireWeekday(Integer value) {
        if (value == null || value < 1 || value > 7) throw new IllegalArgumentException("请选择每周几发送（1-7，周一到周日）");
        return value;
    }

    private int requireMonthDay(Integer value) {
        if (value == null || value < 1 || value > 31) throw new IllegalArgumentException("请选择每月几号发送（1-31）");
        return value;
    }

    private int requireIntervalDays(Integer value) {
        if (value == null || value < 1 || value > 365) throw new IllegalArgumentException("自定义间隔天数需在 1-365 之间");
        return value;
    }

    private Integer normalizeRecurrenceDay(String frequency, Integer value) {
        return FREQ_WEEKLY.equals(frequency) || FREQ_MONTHLY.equals(frequency) ? value : null;
    }

    private Integer normalizeIntervalDays(String frequency, Integer value) {
        return FREQ_CUSTOM.equals(frequency) ? requireIntervalDays(value) : null;
    }

    private String normalizeClientRequestId(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String normalizeContentType(String value) {
        return CONTENT_HTML.equalsIgnoreCase(value == null ? "" : value.strip()) ? CONTENT_HTML : CONTENT_PLAIN;
    }

    private String normalizeTemplateId(String value) {
        String normalized = value == null || value.isBlank() ? "none" : value.strip().toLowerCase(Locale.ROOT);
        return TEMPLATES.contains(normalized) ? normalized : "none";
    }

    private void setNullableInt(PreparedStatement statement, int index, Integer value) throws java.sql.SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.INTEGER);
        else statement.setInt(index, value);
    }

    private void requireUser(Long userId) {
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, UNAUTHORIZED_MESSAGE);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
