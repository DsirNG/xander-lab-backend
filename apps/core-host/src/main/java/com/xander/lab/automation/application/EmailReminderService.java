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
import com.xander.lab.automation.spi.EmailReminderDraft;
import com.xander.lab.automation.spi.EmailReminderPort;
import com.xander.lab.automation.spi.EmailReminderView;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 定时邮件提醒的领域服务；同时是 {@link EmailReminderPort} 在 automation 域这一侧的实现。
 *
 * <h2>为什么同一个能力有两套公开方法</h2>
 * <p>本类同时服务两类调用方，它们的需要不同：</p>
 * <ul>
 *   <li><b>自己的 HTTP 层</b>（{@code EmailReminderController}）：要前端页面渲染所需的
 *       <b>全字段</b> {@code EmailReminderTaskVO}（20 个字段，含 {@code senderEmail} /
 *       {@code contentType} / {@code sendAttempts} / 时间戳等）。</li>
 *   <li><b>智能体工具</b>（{@code EmailReminderTool} 等）：只要 10 个字段，通过
 *       {@link EmailReminderPort} 的 {@link EmailReminderView} 拿。</li>
 * </ul>
 * <p>两者不是"同一个方法两个返回类型"—— Java 不允许只靠返回类型重载。
 * 所以按本仓库既有的做法（见 {@code docs/migration/adr-contract-boundaries.md} §5.2）：
 * <b>端口方法拿规范名</b>（{@code updateStatus}），<b>同域的富方法加后缀说明它服务谁</b>
 * （{@code updateStatusForView}）。</p>
 *
 * <h2>为什么端口方法是转调 + 换算，而不是把实现体搬过来</h2>
 * <p>实现体一行没动：端口方法调同域的富方法，再把 VO 收窄成 {@link EmailReminderView}。
 * 多一次内存里的对象构造，换来的是"这次抽取不改变任何行为"可以被直接看出来 ——
 * 而这次改动要证明的正是这一点。富方法自身的行为由既有的测试守着。</p>
 */
@Service
@RequiredArgsConstructor
public class EmailReminderService implements EmailReminderPort {

    // ⚠️ STATUS_PENDING / STATUS_PAUSED 刻意不在这里声明：它们由 EmailReminderPort 拥有
    // （调用方要 switch 它们，所以它们必须跟着端口走，否则调用方为了拿一个字符串又要 import
    // 实现类）。本类 implements 该端口，所以 EmailReminderService.STATUS_PENDING 仍然可用 ——
    // 它解析到继承来的那个。留在这里会变成第二份可以漂移的常量表。
    // 下面三个只有本域用（调度器与统计口径），不属于跨域词汇，所以留在实现方。

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

    /** 当前用户全部定时邮件任务（不分页），status / search 可组合筛选；
     * 供智能体一次调用拿全量，无需逐页查询。
     *
     * <p>返回类型从 {@code EmailReminderTaskVO} 换成了契约读模型
     * {@link EmailReminderView}：本方法的<b>唯一</b>调用方是
     * {@code QueryEmailRemindersTool}（控制器走的是 {@link #list}），
     * 而那个工具只读 10 个字段。换成收窄的读模型之后，实现方不必再为一个跨域调用方
     * 构造 20 个字段（含 {@code senderEmail} 与若干时间戳）。</p> */
    @Override
    @Transactional(readOnly = true)
    public List<EmailReminderView> listAll(Long userId, String status, String search) {
        requireUser(userId);
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
        return taskMapper.selectList(wrapper).stream()
                .map(task -> toView(toVO(task)))
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
            EmailReminderTask existing = findTaskByClientRequestId(userId, clientRequestId);
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

    /**
     * {@inheritDoc}
     *
     * <p>换算而不是另写一份实现：把草稿装回域请求体，转调上面那个方法，再把 VO 收窄。
     * 校验、幂等、限流、时区解析全都在那一个实现体里，只有一份 ——
     * 复制一份到这里意味着以后改限流规则要改两处，而且不会有任何编译错误提醒。</p>
     */
    @Override
    public EmailReminderView create(Long userId, EmailReminderDraft draft) {
        return toView(create(userId, toRequest(draft)));
    }

    /**
     * 改状态并返回<b>前端要的全字段 VO</b>；同域调用方是 {@code EmailReminderController}。
     *
     * <p>名字带 {@code ForView} 后缀，是因为端口方法 {@link #updateStatus(Long, Long, String)}
     * 已经占用了规范名 {@code updateStatus}：两者参数表完全相同、只差返回类型，
     * 而 Java 不允许只靠返回类型重载。按本仓库既有的做法 ——
     * <b>端口方法拿规范名，同域的富方法加后缀说明它服务谁</b>。</p>
     */
    @Transactional
    public EmailReminderTaskVO updateStatusForView(Long userId, Long taskId, String requestedStatus) {
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

    /**
     * {@inheritDoc}
     *
     * <p>与 {@link #updateStatusForView} 同一实现体，只换返回类型。两个方法都留着的理由见
     * 那个方法上的注释：HTTP 层要全字段 VO，智能体只要 10 个字段的读模型。</p>
     */
    @Override
    public EmailReminderView updateStatus(Long userId, Long taskId, String requestedStatus) {
        return toView(updateStatusForView(userId, taskId, requestedStatus));
    }

    /** {@inheritDoc} */
    @Override
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

    /**
     * {@inheritDoc}
     *
     * <p>返回类型同样是收窄后的读模型：本方法的唯一调用方是
     * {@code EmailReminderTool#reconcile}，它只读 {@code id} / {@code status} /
     * {@code scheduledAt} 等几个字段来判定"上一次调用到底成没成"。</p>
     */
    @Override
    @Transactional(readOnly = true)
    public EmailReminderView findByClientRequestId(Long userId, String clientRequestId) {
        requireUser(userId);
        String normalized = normalizeClientRequestId(clientRequestId);
        if (normalized == null) {
            return null;
        }
        EmailReminderTask task = findTaskByClientRequestId(userId, normalized);
        return task == null || task.getDeletedAt() != null ? null : toView(toVO(task));
    }

    private EmailReminderTask findTaskByClientRequestId(Long userId, String clientRequestId) {
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

    /**
     * 富 VO → 契约读模型。这是<b>唯一</b>一处往 {@link EmailReminderView} 落字段的地方。
     *
     * <p>为什么不写一个"实体 → 读模型"的映射（那样读路径可以少构造一个 VO）：
     * 那会变成两份字段映射，而两份迟早不一致 —— 而且不一致的症状是"列表接口和创建接口
     * 对同一条任务返回不同的值"，很难发现。这里选择让读路径也经过 {@link #toVO}，
     * 换来"只有一处映射"这个性质。{@code toVO} 是纯函数（没有外部调用），
     * 多构造一个对象没有可观测代价。</p>
     *
     * <p>逐个字段手写，<b>不要</b> {@code BeanUtils.copyProperties} / 反射：字段名对不上时
     * 反射版本静默留 {@code null}，手写版本编译期报错。</p>
     *
     * <p>⚠️ {@code frequency} 与 {@code timezone} 的兜底（{@code null} → {@code ONCE} /
     * {@code Asia/Shanghai}）已经在 {@link #toVO} 里做了，这里<b>不要</b>再兜一次 ——
     * 兜两次就是两份规则。落在 {@code EmailReminderServiceTest} 里。</p>
     */
    private EmailReminderView toView(EmailReminderTaskVO vo) {
        if (vo == null) return null;
        return new EmailReminderView(
                vo.getId(),
                vo.getRecipientEmail(),
                vo.getSubject(),
                vo.getMessage(),
                vo.getFrequency(),
                vo.getTimezone(),
                vo.getSendTime(),
                vo.getScheduledAt(),
                vo.getStatus(),
                vo.getErrorMessage(),
                vo.getSentAt());
    }

    /**
     * 契约草稿 → 域请求体。
     *
     * <p>这里刻意<b>不</b>做任何校验或补默认值：{@code create} 的实现体已经做了
     * （默认值、幂等、限流、时区解析、主题换行检查）。在这里再补一次会变成第二份规则，
     * 而且两份迟早不一致。换算就只做换算。</p>
     */
    private EmailReminderCreateRequest toRequest(EmailReminderDraft draft) {
        EmailReminderCreateRequest request = new EmailReminderCreateRequest();
        request.setClientRequestId(draft.clientRequestId());
        request.setRecipientEmail(draft.recipientEmail());
        request.setSubject(draft.subject());
        request.setMessage(draft.message());
        request.setFrequency(draft.frequency());
        request.setTimezone(draft.timezone());
        request.setScheduledAt(draft.scheduledAt());
        request.setSendTime(draft.sendTime());
        request.setRecurrenceDay(draft.recurrenceDay());
        request.setIntervalDays(draft.intervalDays());
        request.setContentType(draft.contentType());
        request.setTemplateId(draft.templateId());
        return request;
    }
}
