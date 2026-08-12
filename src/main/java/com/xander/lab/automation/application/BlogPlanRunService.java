package com.xander.lab.automation.application;

import com.xander.lab.automation.api.BlogPlanRunVO;
import com.xander.lab.automation.domain.BlogContentPlan;
import com.xander.lab.automation.domain.BlogNotification;
import com.xander.lab.automation.domain.BlogPlanRun;
import com.xander.lab.automation.domain.EmailReminderScheduleCalculator;
import com.xander.lab.automation.infrastructure.BlogContentPlanMapper;
import com.xander.lab.automation.infrastructure.BlogNotificationMapper;
import com.xander.lab.automation.infrastructure.BlogPlanRunMapper;
import com.xander.lab.automation.infrastructure.BlogPlanSseService;
import com.xander.lab.blogagent.api.BlogAgentTaskCreateRequest;
import com.xander.lab.blogagent.api.BlogAgentTaskVO;
import com.xander.lab.blogagent.application.BlogAgentService;
import com.xander.lab.blogagent.application.ComplianceReview;
import com.xander.lab.blogagent.domain.BlogAgentTask;
import com.xander.lab.integration.mcp.csdn.application.CsdnCredentialService;
import com.xander.lab.identity.security.UserContext;
import com.xander.lab.publishing.application.PublicationService;
import com.xander.lab.publishing.domain.PublishCommand;
import com.xander.lab.publishing.domain.PublishResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/** Executes one plan trigger: headless generation, compliance gate, local and
 * (optional) CSDN publication, durable run status, notifications, and plan
 * rescheduling. Network/model calls stay outside database transactions. */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlogPlanRunService {

    private final BlogPlanRunMapper runMapper;
    private final BlogContentPlanMapper planMapper;
    private final BlogNotificationMapper notificationMapper;
    private final BlogAgentService agentService;
    private final PublicationService publicationService;
    private final CsdnCredentialService csdnCredentialService;
    private final BlogPlanSseService sseService;
    private final TransactionTemplate transactionTemplate;

    /** Creates the run row for a due trigger and reschedules the plan atomically.
     * Returns the run if newly created, or null when a duplicate is detected. */
    public BlogPlanRun createScheduledRun(BlogContentPlan plan, Instant scheduledAt, String processingToken) {
        return transactionTemplate.execute(status -> {
            boolean existed = runMapper.existsForPlanAndScheduledAt(plan.getId(), scheduledAt);
            if (existed) {
                return null;
            }
            return insertRun(plan, scheduledAt, BlogPlanRun.TRIGGER_SCHEDULED, processingToken);
        });
    }

    /** Creates a MANUAL run for an immediate user-initiated trigger. */
    public BlogPlanRun createManualRun(BlogContentPlan plan) {
        return transactionTemplate.execute(status -> insertRun(
                plan, Instant.now(), BlogPlanRun.TRIGGER_MANUAL, UUID.randomUUID().toString()));
    }

    private BlogPlanRun insertRun(BlogContentPlan plan, Instant scheduledAt,
                                  String triggerType, String processingToken) {
        BlogPlanRun run = new BlogPlanRun();
        run.setPlanId(plan.getId());
        run.setScheduledAt(scheduledAt);
        run.setTriggerType(triggerType);
        run.setStatus(BlogPlanRun.STATUS_GENERATING);
        run.setCsdnStatus(plan.getSyncCsdn() == Boolean.TRUE
                ? BlogPlanRun.CSDN_PENDING : BlogPlanRun.CSDN_UNSET);
        run.setProcessingToken(processingToken);
        run.setClaimedAt(Instant.now());
        run.setStartedAt(Instant.now());
        runMapper.insert(run);
        return runMapper.selectById(run.getId());
    }

    /** Full execution pipeline for a run. Must run in a background worker
     * thread, never the poller. Publishes are carried out independently: a CSDN
     * failure never rolls back the local post. */
    public void executeRun(BlogPlanRun run, BlogContentPlan plan) {
        Long userId = plan.getUserId();
        UserContext.setUserId(userId);
        String stage = BlogPlanRun.STAGE_GENERATE;
        try {
            BlogAgentTask task = createTask(plan, userId);
            stage = BlogPlanRun.STAGE_GENERATE;
            markAgentTask(run.getId(), task.getId());

            stage = BlogPlanRun.STAGE_GENERATE;
            agentService.run(task.getId(), userId);

            stage = BlogPlanRun.STAGE_REVIEW;
            ComplianceReview review = agentService.reviewCompliance(task.getId(), userId);
            if (!review.pass()) {
                rejectRun(run, plan, review.reason());
                return;
            }

            stage = BlogPlanRun.STAGE_PUBLISH;
            BlogAgentTaskVO ready = agentService.get(task.getId(), userId);
            long localPostId = publishLocal(run.getId(), plan, ready);
            markLocalPost(run.getId(), localPostId);

            if (plan.getSyncCsdn() == Boolean.TRUE) {
                publishCsdn(run.getId(), plan, ready);
            }

            finishSucceeded(run.getId());
        } catch (RuntimeException e) {
            String message = limit(rootMessage(e), 1000);
            failRun(run.getId(), stage, message);
            notifyUser(userId, BlogNotification.TYPE_RUN_FAILED,
                    "定时发文执行失败",
                    "计划《" + plan.getTopic() + "》本次执行失败：" + message,
                    plan.getId(), run.getId());
            log.warn("[BlogPlan] plan={} run={} failed at stage={}: {}", plan.getId(), run.getId(), stage, message);
        } finally {
            UserContext.clear();
        }
    }

    BlogAgentTask createTask(BlogContentPlan plan, Long userId) {
        // Consume the next topic from the queue atomically so concurrent runs
        // of the same plan never use the same topic twice.
        String topic = consumeNextTopic(plan.getId());
        StringBuilder input = new StringBuilder(topic == null ? plan.getTopic() : topic);
        if (StringUtils.hasText(plan.getAudience())) {
            input.append("\n目标读者：").append(plan.getAudience());
        }
        if (StringUtils.hasText(plan.getTone())) {
            input.append("\n文风语气：").append(plan.getTone());
        }
        BlogAgentTaskCreateRequest request = new BlogAgentTaskCreateRequest();
        request.setInput(input.toString());
        return agentService.create(userId, request);
    }

    /** Locks the plan row, returns the next queued topic, and advances the
     * queue index. Returns null when the plan has no multi-day topic queue. */
    private String consumeNextTopic(Long planId) {
        return transactionTemplate.execute(status -> {
            BlogContentPlan locked = planMapper.selectByIdForUpdate(planId);
            if (locked == null) return null;
            List<String> queue = locked.topics();
            if (queue.isEmpty()) return null;
            int index = locked.getNextTopicIndex() == null ? 0 : locked.getNextTopicIndex();
            String topic = queue.get(index % queue.size());
            locked.setNextTopicIndex(index + 1);
            planMapper.updateById(locked);
            return topic;
        });
    }

    private long publishLocal(Long runId, BlogContentPlan plan, BlogAgentTaskVO ready) {
        BlogAgentTask task = ready.getTask();
        PublishCommand command = new PublishCommand(
                -runId, plan.getUserId(), task.getTitle(), task.getSummary(), task.getContent(),
                task.getCategoryId(), null, ready.getTags());
        PublishResult result = publicationService.publish(command, "local");
        return Long.parseLong(result.externalId());
    }

    private void publishCsdn(Long runId, BlogContentPlan plan, BlogAgentTaskVO ready) {
        if (!csdnCredentialService.isAuthorized(plan.getUserId())) {
            updateCsdnFailure(runId, "AUTH_REQUIRED",
                    "CSDN 尚未授权，本次已跳过同步，请先在个人中心完成 CSDN 授权");
            return;
        }
        try {
            BlogAgentTask task = ready.getTask();
            // CSDN 原文链接按 -taskId 的绝对值生成，必须用本地文章ID（localPostId），
            // 否则回链指向 /blog/{runId}；同时 publish_record.task_id = -postId 才能被 csdnSynced 识别为已同步
            long localPostId = requireRun(runId).getLocalPostId();
            PublishCommand command = new PublishCommand(
                    -localPostId, plan.getUserId(), task.getTitle(), task.getSummary(), task.getContent(),
                    task.getCategoryId(), null, ready.getTags());
            PublishResult result = publicationService.publish(command, "csdn");
            transactionTemplate.executeWithoutResult(status -> {
                BlogPlanRun run = requireRun(runId);
                run.setCsdnStatus(BlogPlanRun.CSDN_SUCCEEDED);
                run.setCsdnExternalId(result.externalId());
                run.setCsdnUrl(result.url());
                runMapper.updateById(run);
            });
        } catch (RuntimeException e) {
            String message = limit(rootMessage(e), 1000);
            updateCsdnFailure(runId, "PUBLISH_FAILED", message);
            notifyUser(plan.getUserId(), BlogNotification.TYPE_CSDN_FAILED,
                    "CSDN 同步失败",
                    "计划《" + plan.getTopic() + "》已在本站发布，但同步 CSDN 失败：" + message,
                    plan.getId(), runId);
        }
    }

    private void updateCsdnFailure(Long runId, String code, String message) {
        transactionTemplate.executeWithoutResult(status -> {
            BlogPlanRun run = requireRun(runId);
            run.setCsdnStatus(BlogPlanRun.CSDN_FAILED);
            run.setCsdnErrorCode(code);
            run.setCsdnErrorMessage(limit(message, 1000));
            runMapper.updateById(run);
        });
    }

    private void rejectRun(BlogPlanRun run, BlogContentPlan plan, String reason) {
        transactionTemplate.executeWithoutResult(status -> {
            BlogPlanRun latest = requireRun(run.getId());
            latest.setStatus(BlogPlanRun.STATUS_REVIEW_REJECTED);
            latest.setReviewPass(false);
            latest.setReviewReason(limit(reason, 1000));
            latest.setFinishedAt(Instant.now());
            runMapper.updateById(latest);
        });
        notifyUser(plan.getUserId(), BlogNotification.TYPE_REVIEW_REJECTED,
                "文章未通过合规审核",
                "计划《" + plan.getTopic() + "》本次生成的内容未通过合规审核：" + limit(reason, 300),
                plan.getId(), run.getId());
    }

    private void finishSucceeded(Long runId) {
        transactionTemplate.executeWithoutResult(status -> {
            BlogPlanRun run = requireRun(runId);
            run.setStatus(BlogPlanRun.STATUS_SUCCEEDED);
            run.setReviewPass(true);
            run.setFinishedAt(Instant.now());
            runMapper.updateById(run);
        });
    }

    private void failRun(Long runId, String stage, String errorMessage) {
        transactionTemplate.executeWithoutResult(status -> {
            BlogPlanRun run = requireRun(runId);
            run.setStatus(BlogPlanRun.STATUS_FAILED);
            run.setStageError(stage);
            run.setErrorMessage(limit(errorMessage, 1000));
            run.setFinishedAt(Instant.now());
            runMapper.updateById(run);
        });
    }

    private void markAgentTask(Long runId, Long agentTaskId) {
        transactionTemplate.executeWithoutResult(status -> {
            BlogPlanRun run = requireRun(runId);
            run.setAgentTaskId(agentTaskId);
            runMapper.updateById(run);
        });
    }

    private void markLocalPost(Long runId, Long localPostId) {
        transactionTemplate.executeWithoutResult(status -> {
            BlogPlanRun run = requireRun(runId);
            run.setLocalPostId(localPostId);
            runMapper.updateById(run);
        });
    }

    public void notifyUser(Long userId, String type, String title, String message,
                           Long planId, Long runId) {
        if (userId == null || userId == 0L) {
            return;
        }
        BlogNotification notification = new BlogNotification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setTitle(title);
        notification.setMessage(limit(message, 1000));
        notification.setPlanId(planId);
        notification.setRunId(runId);
        notification.setIsRead(false);
        notificationMapper.insert(notification);
        sseService.push(userId, type, title, notification.getMessage(), planId, runId);
    }

    private BlogPlanRun requireRun(Long runId) {
        BlogPlanRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new IllegalStateException("计划执行记录不存在：" + runId);
        }
        return run;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? "执行失败" : message;
    }

    private String limit(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Computes the next daily trigger after the given reference instant. */
    public Instant resolveNextRun(BlogContentPlan plan, Instant reference) {
        ZoneId zone = EmailReminderScheduleCalculator.resolveZone(plan.getTimezone());
        return EmailReminderScheduleCalculator.resolveNextDailyAfter(
                zone,
                EmailReminderScheduleCalculator.parseSendTimes(plan.allTriggerTimes()),
                reference
        );
    }

    public BlogPlanRunVO toRunVO(BlogPlanRun run) {
        if (run == null) return null;
        return BlogPlanRunVO.builder()
                .id(run.getId())
                .planId(run.getPlanId())
                .scheduledAt(run.getScheduledAt())
                .triggerType(run.getTriggerType())
                .status(run.getStatus())
                .stageError(run.getStageError())
                .errorMessage(run.getErrorMessage())
                .agentTaskId(run.getAgentTaskId())
                .localPostId(run.getLocalPostId())
                .reviewPass(run.getReviewPass())
                .reviewReason(run.getReviewReason())
                .csdnStatus(run.getCsdnStatus())
                .csdnErrorCode(run.getCsdnErrorCode())
                .csdnErrorMessage(run.getCsdnErrorMessage())
                .csdnExternalId(run.getCsdnExternalId())
                .csdnUrl(run.getCsdnUrl())
                .startedAt(run.getStartedAt())
                .finishedAt(run.getFinishedAt())
                .createdAt(run.getCreatedAt())
                .build();
    }
}