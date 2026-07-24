package com.xander.lab.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xander.lab.dto.emailreminder.EmailReminderCreateRequest;
import com.xander.lab.dto.emailreminder.EmailReminderTaskVO;
import com.xander.lab.entity.EmailReminderTask;
import com.xander.lab.mapper.EmailReminderTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class EmailReminderService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_SENDING = "SENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";

    private static final String UNAUTHORIZED_MESSAGE = "未登录或登录已过期";

    private final EmailReminderTaskMapper taskMapper;
    private final Clock clock;

    @Value("${mail.from}")
    private String senderEmail;

    @Value("${email-reminder.minimum-lead-seconds:30}")
    private long minimumLeadSeconds;

    @Value("${email-reminder.max-active-per-user:50}")
    private long maxActivePerUser;

    @Value("${email-reminder.max-created-per-day:20}")
    private long maxCreatedPerDay;

    @Transactional(readOnly = true)
    public List<EmailReminderTaskVO> list(Long userId) {
        requireUser(userId);
        return taskMapper.selectList(new LambdaQueryWrapper<EmailReminderTask>()
                        .eq(EmailReminderTask::getUserId, userId)
                        .isNull(EmailReminderTask::getDeletedAt)
                        .orderByDesc(EmailReminderTask::getCreatedAt)
                        .orderByDesc(EmailReminderTask::getId))
                .stream()
                .map(this::toVO)
                .toList();
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

        Instant now = clock.instant();
        Instant scheduledAt = request.getScheduledAt().toInstant();
        if (scheduledAt.isBefore(now.plusSeconds(Math.max(1, minimumLeadSeconds)))) {
            throw new IllegalArgumentException(
                    "发送时间至少需要晚于当前时间 " + Math.max(1, minimumLeadSeconds) + " 秒"
            );
        }

        long activeCount = taskMapper.selectCount(new LambdaQueryWrapper<EmailReminderTask>()
                .eq(EmailReminderTask::getUserId, userId)
                .isNull(EmailReminderTask::getDeletedAt)
                .in(EmailReminderTask::getStatus, STATUS_PENDING, STATUS_PAUSED, STATUS_SENDING));
        if (activeCount >= maxActivePerUser) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "进行中的定时邮件任务已达到上限，请先完成或删除部分任务"
            );
        }

        long dailyCreatedCount = taskMapper.selectCount(new LambdaQueryWrapper<EmailReminderTask>()
                .eq(EmailReminderTask::getUserId, userId)
                .ge(EmailReminderTask::getCreatedAt, now.minus(1, ChronoUnit.DAYS)));
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
        task.setScheduledAt(scheduledAt);
        task.setStatus(STATUS_PENDING);
        task.setSendAttempts(0);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        taskMapper.insert(task);
        return toVO(task);
    }

    @Transactional
    public EmailReminderTaskVO updateStatus(Long userId, Long taskId, String requestedStatus) {
        requireUser(userId);
        EmailReminderTask task = requireOwnedTask(taskId, userId);
        String targetStatus = requestedStatus.toUpperCase(Locale.ROOT);

        if (targetStatus.equals(task.getStatus())) {
            return toVO(task);
        }

        Instant now = clock.instant();
        int updated;
        if (STATUS_PAUSED.equals(targetStatus)) {
            if (!STATUS_PENDING.equals(task.getStatus())) {
                throw conflict("当前状态无法暂停");
            }
            updated = taskMapper.pauseOwnedTask(taskId, userId, now);
        } else if (STATUS_PENDING.equals(targetStatus)) {
            if (!STATUS_PAUSED.equals(task.getStatus()) && !STATUS_FAILED.equals(task.getStatus())) {
                throw conflict("当前状态无法恢复");
            }
            if (!task.getScheduledAt().isAfter(now)) {
                throw new IllegalArgumentException("计划发送时间已过，请删除后重新创建任务");
            }
            updated = taskMapper.resumeOwnedTask(taskId, userId, now);
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
        if (taskMapper.deleteOwnedTaskUnlessSending(taskId, userId, clock.instant()) != 1) {
            EmailReminderTask latest = requireOwnedTask(taskId, userId);
            if (STATUS_SENDING.equals(latest.getStatus())) {
                throw conflict("邮件正在发送，暂时不能删除");
            }
            throw conflict("任务状态已变化，请刷新后重试");
        }
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
