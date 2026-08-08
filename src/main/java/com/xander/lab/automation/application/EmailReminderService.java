package com.xander.lab.automation.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xander.lab.automation.api.EmailReminderCreateRequest;
import com.xander.lab.automation.api.EmailReminderListResult;
import com.xander.lab.automation.api.EmailReminderStatsVO;
import com.xander.lab.automation.api.EmailReminderTaskVO;
import com.xander.lab.automation.domain.EmailReminderTask;
import com.xander.lab.automation.domain.EmailReminderScheduleCalculator;
import com.xander.lab.automation.domain.EmailReminderMailTemplates;
import com.xander.lab.automation.infrastructure.EmailReminderTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class EmailReminderService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_SENDING = "SENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";

    private static final String UNAUTHORIZED_MESSAGE = "未登录或登录已过期";
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final Set<Integer> ALLOWED_PAGE_SIZES = Set.of(5, 10, 15, 20);
    private static final Set<String> LIST_STATUSES = Set.of(
            STATUS_PENDING, STATUS_PAUSED, STATUS_SENDING, STATUS_SENT, STATUS_FAILED
    );

    private final EmailReminderTaskMapper taskMapper;

    @Value("${mail.from}")
    private String senderEmail;

    @Value("${email-reminder.minimum-lead-seconds:30}")
    private long minimumLeadSeconds;

    @Value("${email-reminder.max-active-per-user:50}")
    private long maxActivePerUser;

    @Value("${email-reminder.max-created-per-day:20}")
    private long maxCreatedPerDay;

    @Transactional(readOnly = true)
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

        LambdaQueryWrapper<EmailReminderTask> wrapper = new LambdaQueryWrapper<EmailReminderTask>()
                .eq(EmailReminderTask::getUserId, userId)
                .isNull(EmailReminderTask::getDeletedAt)
                .eq(statusFilter != null, EmailReminderTask::getStatus, statusFilter)
                .and(!keyword.isEmpty(), q -> q
                        .like(EmailReminderTask::getSubject, keyword)
                        .or()
                        .like(EmailReminderTask::getRecipientEmail, keyword))
                .orderByDesc(EmailReminderTask::getCreatedAt)
                .orderByDesc(EmailReminderTask::getId);

        Page<EmailReminderTask> pageParam = new Page<>(safePage, safeSize);
        Page<EmailReminderTask> result = taskMapper.selectPage(pageParam, wrapper);

        Page<EmailReminderTaskVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(this::toVO).toList());
        return EmailReminderListResult.of(voPage, buildStats(userId));
    }

    private EmailReminderStatsVO buildStats(Long userId) {
        long total = taskMapper.selectCount(ownedTasksWrapper(userId));
        long pending = taskMapper.selectCount(
                ownedTasksWrapper(userId).eq(EmailReminderTask::getStatus, STATUS_PENDING));
        long sending = taskMapper.selectCount(
                ownedTasksWrapper(userId).eq(EmailReminderTask::getStatus, STATUS_SENDING));
        long sent = taskMapper.selectCount(
                ownedTasksWrapper(userId).eq(EmailReminderTask::getStatus, STATUS_SENT));

        return EmailReminderStatsVO.builder()
                .total(total)
                .active(pending + sending)
                .sent(sent)
                .pending(pending)
                .build();
    }

    private LambdaQueryWrapper<EmailReminderTask> ownedTasksWrapper(Long userId) {
        return new LambdaQueryWrapper<EmailReminderTask>()
                .eq(EmailReminderTask::getUserId, userId)
                .isNull(EmailReminderTask::getDeletedAt);
    }

    private String normalizeListStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.strip().toUpperCase(Locale.ROOT);
        if ("ALL".equals(normalized)) {
            return null;
        }
        if ("SCHEDULED".equals(normalized) || "ACTIVE".equals(normalized)) {
            return STATUS_PENDING;
        }
        if ("PROCESSING".equals(normalized)) {
            return STATUS_SENDING;
        }
        if (!LIST_STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的任务状态筛选");
        }
        return normalized;
    }

    @Transactional
    public EmailReminderTaskVO create(Long userId, EmailReminderCreateRequest request) {
        requireUser(userId);
        if (taskMapper.lockUserForReminderCreation(userId) == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, UNAUTHORIZED_MESSAGE);
        }

        String clientRequestId = normalizeClientRequestId(request.getClientRequestId());
        if (clientRequestId != null) {
            EmailReminderTask existing = findByClientRequestId(userId, clientRequestId);
            if (existing != null) {
                if (existing.getDeletedAt() != null) {
                    throw conflict("该幂等请求标识已被删除任务使用，请生成新的标识");
                }
                return toVO(existing);
            }
        }

        String frequency = EmailReminderScheduleCalculator.normalizeFrequency(request.getFrequency());
        ZoneId zone = EmailReminderScheduleCalculator.resolveZone(request.getTimezone());
        Instant now = Instant.now();
        Instant onceAt = request.getScheduledAt() == null ? null : request.getScheduledAt().toInstant();
        Instant scheduledAt = EmailReminderScheduleCalculator.resolveFirstFire(
                frequency,
                zone,
                onceAt,
                request.getSendTime(),
                request.getRecurrenceDay(),
                request.getIntervalDays(),
                now
        );

        if (!taskMapper.isScheduledAtLeastLeadTime(
                scheduledAt,
                Math.max(1, minimumLeadSeconds)
        )) {
            throw new IllegalArgumentException(
                    "发送时间至少需要晚于当前时间 " + Math.max(1, minimumLeadSeconds) + " 秒"
            );
        }

        long activeCount = taskMapper.countActiveTasks(userId);
        if (activeCount >= maxActivePerUser) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "进行中的定时邮件任务已达到上限，请先完成或删除部分任务"
            );
        }

        long dailyCreatedCount = taskMapper.countCreatedInPastDay(userId);
        if (dailyCreatedCount >= maxCreatedPerDay) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "过去 24 小时创建的定时邮件已达到上限"
            );
        }

        String subject = request.getSubject().strip();
        if (subject.contains("\r") || subject.contains("\n")) {
            throw new IllegalArgumentException("邮件主题不能包含换行符");
        }

        EmailReminderTask task = new EmailReminderTask();
        task.setUserId(userId);
        task.setClientRequestId(clientRequestId);
        task.setRecipientEmail(request.getRecipientEmail().strip().toLowerCase(Locale.ROOT));
        task.setSubject(subject);
        task.setMessage(request.getMessage().strip());
        task.setContentType(EmailReminderMailTemplates.normalizeContentType(request.getContentType()));
        task.setTemplateId(EmailReminderMailTemplates.normalizeTemplateId(request.getTemplateId()));
        task.setFrequency(frequency);
        task.setTimezone(zone.getId());
        task.setSendTime(normalizeSendTime(frequency, request.getSendTime()));
        task.setRecurrenceDay(normalizeRecurrenceDay(frequency, request.getRecurrenceDay()));
        task.setIntervalDays(normalizeIntervalDays(frequency, request.getIntervalDays()));
        task.setScheduledAt(scheduledAt);
        task.setStatus(STATUS_PENDING);
        task.setSendAttempts(0);
        taskMapper.insert(task);
        EmailReminderTask created = taskMapper.selectById(task.getId());
        if (created == null) {
            throw new IllegalStateException("定时邮件任务创建后无法读取");
        }
        return toVO(created);
    }

    @Transactional
    public EmailReminderTaskVO updateStatus(Long userId, Long taskId, String requestedStatus) {
        requireUser(userId);
        EmailReminderTask task = requireOwnedTask(taskId, userId);
        String targetStatus = requestedStatus.toUpperCase(Locale.ROOT);

        if (targetStatus.equals(task.getStatus())) {
            return toVO(task);
        }

        int updated;
        if (STATUS_PAUSED.equals(targetStatus)) {
            if (!STATUS_PENDING.equals(task.getStatus())) {
                throw conflict("当前状态无法暂停");
            }
            updated = taskMapper.pauseOwnedTask(taskId, userId);
        } else if (STATUS_PENDING.equals(targetStatus)) {
            if (!STATUS_PAUSED.equals(task.getStatus()) && !STATUS_FAILED.equals(task.getStatus())) {
                throw conflict("当前状态无法恢复");
            }
            Instant now = Instant.now();
            Instant nextAt = task.getScheduledAt();
            boolean recurring = !EmailReminderScheduleCalculator.FREQ_ONCE.equals(
                    EmailReminderScheduleCalculator.normalizeFrequency(task.getFrequency())
            );
            if (nextAt == null || !nextAt.isAfter(now)) {
                if (!recurring) {
                    throw new IllegalArgumentException("计划发送时间已过，请删除后重新创建任务");
                }
                ZoneId zone = EmailReminderScheduleCalculator.resolveZone(task.getTimezone());
                nextAt = EmailReminderScheduleCalculator.resolveNextAfterSend(
                        task.getFrequency(),
                        zone,
                        task.getSendTime(),
                        task.getRecurrenceDay(),
                        task.getIntervalDays(),
                        task.getScheduledAt(),
                        now
                );
                if (nextAt == null || !nextAt.isAfter(now)) {
                    throw new IllegalArgumentException("无法计算下一次发送时间，请删除后重新创建任务");
                }
                updated = taskMapper.resumeOwnedTaskAt(taskId, userId, nextAt);
            } else {
                updated = taskMapper.resumeOwnedTask(taskId, userId);
            }
        } else {
            throw new IllegalArgumentException("任务状态只支持 PENDING 或 PAUSED");
        }

        if (updated != 1) {
            EmailReminderTask latest = requireOwnedTask(taskId, userId);
            throw conflict("任务状态已变化，请刷新后重试（当前状态：" + latest.getStatus() + "）");
        }
        return toVO(requireOwnedTask(taskId, userId));
    }

    @Transactional
    public void delete(Long userId, Long taskId) {
        requireUser(userId);
        EmailReminderTask task = requireOwnedTask(taskId, userId);
        if (STATUS_SENDING.equals(task.getStatus())) {
            throw conflict("邮件正在发送，暂时不能删除");
        }
        if (taskMapper.deleteOwnedTaskUnlessSending(taskId, userId) != 1) {
            EmailReminderTask latest = requireOwnedTask(taskId, userId);
            if (STATUS_SENDING.equals(latest.getStatus())) {
                throw conflict("邮件正在发送，暂时不能删除");
            }
            throw conflict("任务状态已变化，请刷新后重试");
        }
    }

    private String normalizeSendTime(String frequency, String sendTime) {
        if (EmailReminderScheduleCalculator.FREQ_ONCE.equals(frequency)) {
            return null;
        }
        return EmailReminderScheduleCalculator.parseSendTime(sendTime)
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
    }

    private Integer normalizeRecurrenceDay(String frequency, Integer recurrenceDay) {
        if (EmailReminderScheduleCalculator.FREQ_WEEKLY.equals(frequency)
                || EmailReminderScheduleCalculator.FREQ_MONTHLY.equals(frequency)) {
            return recurrenceDay;
        }
        return null;
    }

    private Integer normalizeIntervalDays(String frequency, Integer intervalDays) {
        if (EmailReminderScheduleCalculator.FREQ_CUSTOM.equals(frequency)) {
            return intervalDays;
        }
        return null;
    }

    private EmailReminderTask requireOwnedTask(Long taskId, Long userId) {
        EmailReminderTask task = taskMapper.selectOne(new LambdaQueryWrapper<EmailReminderTask>()
                .eq(EmailReminderTask::getId, taskId)
                .eq(EmailReminderTask::getUserId, userId)
                .isNull(EmailReminderTask::getDeletedAt));
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "定时邮件任务不存在");
        }
        return task;
    }

    private EmailReminderTask findByClientRequestId(Long userId, String clientRequestId) {
        return taskMapper.selectOne(new LambdaQueryWrapper<EmailReminderTask>()
                .eq(EmailReminderTask::getUserId, userId)
                .eq(EmailReminderTask::getClientRequestId, clientRequestId));
    }

    private String normalizeClientRequestId(String clientRequestId) {
        if (clientRequestId == null || clientRequestId.isBlank()) {
            return null;
        }
        return clientRequestId.strip();
    }

    private void requireUser(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, UNAUTHORIZED_MESSAGE);
        }
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private EmailReminderTaskVO toVO(EmailReminderTask task) {
        return EmailReminderTaskVO.builder()
                .id(task.getId())
                .clientRequestId(task.getClientRequestId())
                .senderEmail(senderEmail)
                .recipientEmail(task.getRecipientEmail())
                .subject(task.getSubject())
                .message(task.getMessage())
                .contentType(EmailReminderMailTemplates.normalizeContentType(task.getContentType()))
                .templateId(EmailReminderMailTemplates.normalizeTemplateId(task.getTemplateId()))
                .frequency(task.getFrequency() == null
                        ? EmailReminderScheduleCalculator.FREQ_ONCE
                        : task.getFrequency())
                .timezone(task.getTimezone() == null ? "Asia/Shanghai" : task.getTimezone())
                .sendTime(task.getSendTime())
                .recurrenceDay(task.getRecurrenceDay())
                .intervalDays(task.getIntervalDays())
                .scheduledAt(task.getScheduledAt())
                .status(task.getStatus())
                .sendAttempts(task.getSendAttempts())
                .errorMessage(task.getErrorMessage())
                .sentAt(task.getSentAt())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .build();
    }
}
