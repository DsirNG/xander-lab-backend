package com.xander.lab.automation.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.automation.api.BlogPlanRunVO;
import com.xander.lab.automation.domain.ContentPlanScheduleCalculator;
import com.xander.lab.automation.host.ContentPlanExecutionProperties;
import com.xander.lab.automation.host.ContentPlanSchedulerProperties;
import com.xander.lab.automation.infrastructure.ContentNotificationStreamService;
import com.xander.lab.automation.infrastructure.RecitationMaterialClient;
import com.xander.lab.blogagent.api.BlogAgentTaskCreateRequest;
import com.xander.lab.blogagent.api.BlogAgentTaskVO;
import com.xander.lab.blogagent.application.BlogAgentPublishService;
import com.xander.lab.blogagent.application.BlogAgentTaskReadService;
import com.xander.lab.blogagent.application.BlogAgentTaskWriteService;
import com.xander.lab.blogagent.domain.BlogAgentTask;
import com.xander.lab.blogagent.infrastructure.BlogAgentModelClient;
import com.xander.lab.core.api.BlogAgentModelContract;
import com.xander.lab.recitation.spi.MaterialView;
import com.xander.lab.publishing.api.BlogPostStatusUpdateRequest;
import com.xander.lab.publishing.api.BlogPostVO;
import com.xander.lab.publishing.application.BlogMutationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Content-owned manual plan execution path.
 *
 * <p>This is deliberately behind a feature gate while the old core-host executor drains. The
 * service owns the content database run row and notification side effects; the model stage is
 * still a stateless call to core-host and local task/post state stays in this deployment unit.
 * External platform synchronization is best-effort per platform: each attempt is persisted
 * independently after local publication, so a vendor failure never rolls back the local post.</p>
 */
@Slf4j
@Service
public class ContentBlogPlanRunExecutionService {

    private static final String STATUS_GENERATING = "GENERATING";
    private static final String STATUS_REVIEWING = "REVIEWING";
    private static final String STATUS_PUBLISHING = "PUBLISHING";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_REVIEW_REJECTED = "REVIEW_REJECTED";
    private static final String STAGE_GENERATE = "generate";
    private static final String STAGE_REVIEW = "review";
    private static final String STAGE_PUBLISH = "publish";
    private static final int DRAFT_STATUS = 0;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final BlogAgentTaskWriteService taskWriteService;
    private final BlogAgentTaskReadService taskReadService;
    private final BlogAgentModelClient modelClient;
    private final BlogAgentPublishService publishService;
    private final BlogMutationService blogMutationService;
    private final BlogPlanRunReadService runReadService;
    private final ContentNotificationStreamService notificationStreamService;
    private final RecitationMaterialClient recitationMaterialClient;
    private final ContentPlanExternalSyncService externalSyncService;
    private final TaskExecutor executor;
    private final ContentPlanExecutionProperties properties;
    private final ContentPlanSchedulerProperties schedulerProperties;

    public ContentBlogPlanRunExecutionService(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            BlogAgentTaskWriteService taskWriteService,
            BlogAgentTaskReadService taskReadService,
            BlogAgentModelClient modelClient,
            BlogAgentPublishService publishService,
            BlogMutationService blogMutationService,
            BlogPlanRunReadService runReadService,
            ContentNotificationStreamService notificationStreamService,
            RecitationMaterialClient recitationMaterialClient,
            ContentPlanExternalSyncService externalSyncService,
            @Qualifier("contentBlogPlanRunExecutor") TaskExecutor executor,
            ContentPlanExecutionProperties properties,
            ContentPlanSchedulerProperties schedulerProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.taskWriteService = taskWriteService;
        this.taskReadService = taskReadService;
        this.modelClient = modelClient;
        this.publishService = publishService;
        this.blogMutationService = blogMutationService;
        this.runReadService = runReadService;
        this.notificationStreamService = notificationStreamService;
        this.recitationMaterialClient = recitationMaterialClient;
        this.externalSyncService = externalSyncService;
        this.executor = executor;
        this.properties = properties;
        this.schedulerProperties = schedulerProperties;
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    /** Polls due plans, recovers stale claims, and enqueues bounded background runs. */
    public void dispatchDuePlans() {
        if (!properties.isEnabled() || !schedulerProperties.isEnabled()) {
            return;
        }
        recoverStaleClaims();
        List<Long> planIds = jdbcTemplate.query(
                "SELECT id FROM blog_content_plan WHERE status = 'ACTIVE' "
                        + "AND deleted_at IS NULL AND next_run_at <= CURRENT_TIMESTAMP(3) "
                        + "ORDER BY next_run_at ASC, id ASC LIMIT ?",
                (rs, rowNum) -> rs.getLong("id"),
                Math.max(1, schedulerProperties.getBatchSize()));
        for (Long planId : planIds) {
            try {
                dispatchScheduled(planId);
            } catch (RuntimeException exception) {
                log.warn("[BlogPlan] content dispatch failed for plan={}: {}",
                        planId, rootMessage(exception));
            }
        }
    }

    private void dispatchScheduled(Long planId) {
        ScheduledClaim claim = claimScheduledPlan(planId);
        if (claim == null) {
            return;
        }
        if (!claim.created()) {
            recoverDuplicateClaim(claim.plan(), claim.processingToken());
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    execute(claim.runId(), claim.plan());
                    completeScheduledPlan(claim.plan(), claim.runId(), claim.processingToken());
                } catch (RuntimeException exception) {
                    failRun(claim.runId(), stageOf(claim.runId()), rootMessage(exception));
                    completeScheduledPlan(claim.plan(), claim.runId(), claim.processingToken());
                    log.warn("[BlogPlan] content scheduled run={} failed: {}",
                            claim.runId(), rootMessage(exception));
                }
            });
        } catch (RuntimeException exception) {
            failRun(claim.runId(), STAGE_GENERATE, rootMessage(exception));
            completeScheduledPlan(claim.plan(), claim.runId(), claim.processingToken());
            throw exception;
        }
    }

    private ScheduledClaim claimScheduledPlan(Long planId) {
        String token = UUID.randomUUID().toString();
        return transactionTemplate.execute(status -> {
            int claimed = jdbcTemplate.update("""
                    UPDATE blog_content_plan
                    SET status = 'RUNNING', processing_token = ?, claimed_at = CURRENT_TIMESTAMP(3),
                        error_message = NULL
                    WHERE id = ? AND deleted_at IS NULL AND status = 'ACTIVE'
                      AND next_run_at <= CURRENT_TIMESTAMP(3)
                    """, token, planId);
            if (claimed != 1) {
                return null;
            }
            List<PlanSnapshot> rows = jdbcTemplate.query(
                    planSelect() + " WHERE id = ? AND deleted_at IS NULL",
                    this::mapPlan, planId);
            if (rows.isEmpty()) {
                throw new IllegalStateException("认领后的内容计划不存在：" + planId);
            }
            PlanSnapshot plan = rows.get(0);
            Instant scheduledAt = plan.nextRunAt() == null ? Instant.now() : plan.nextRunAt();
            int inserted = jdbcTemplate.update("""
                    INSERT IGNORE INTO blog_plan_run (
                        plan_id, scheduled_at, trigger_type, status,
                        csdn_status, juejin_status, processing_token, claimed_at, started_at
                    ) VALUES (?, ?, 'SCHEDULED', 'GENERATING', ?, ?, ?, CURRENT_TIMESTAMP(3),
                              CURRENT_TIMESTAMP(3))
                    """, plan.id(), java.sql.Timestamp.from(scheduledAt),
                    plan.syncCsdn() ? "PENDING" : "UNSET",
                    plan.syncJuejin() ? "PENDING" : "UNSET", token);
            Long runId = jdbcTemplate.query(
                    "SELECT id FROM blog_plan_run WHERE plan_id = ? AND scheduled_at = ?",
                    (rs, rowNum) -> rs.getLong("id"), plan.id(),
                    java.sql.Timestamp.from(scheduledAt)).stream().findFirst().orElse(null);
            if (runId == null) {
                throw new IllegalStateException("创建计划执行记录失败");
            }
            return new ScheduledClaim(plan, runId, inserted == 1, token);
        });
    }

    private void recoverDuplicateClaim(PlanSnapshot plan, String token) {
        String message = "同一次触发已存在执行记录，已跳过";
        if (plan.runOnce()) {
            failPlan(plan, token, message);
        } else {
            recoverPlan(plan, token, nextRun(plan, Instant.now()), message);
        }
    }

    private void completeScheduledPlan(PlanSnapshot plan, Long runId, String token) {
        RunTerminal run = jdbcTemplate.query(
                "SELECT status, error_message, review_reason, scheduled_at "
                        + "FROM blog_plan_run WHERE id = ?",
                (rs, rowNum) -> new RunTerminal(rs.getString("status"),
                        rs.getString("error_message"), rs.getString("review_reason"),
                        toInstant(rs, "scheduled_at")), runId).stream().findFirst().orElse(null);
        if (run == null) {
            throw new IllegalStateException("计划执行记录不存在：" + runId);
        }
        if (plan.runOnce()) {
            if (STATUS_SUCCEEDED.equals(run.status())) {
                jdbcTemplate.update("""
                        UPDATE blog_content_plan
                        SET status = 'FINISHED', next_run_at = NULL, last_run_at = ?,
                            processing_token = NULL, claimed_at = NULL, error_message = NULL
                        WHERE id = ? AND status = 'RUNNING' AND processing_token = ?
                        """, timestamp(run.scheduledAt()), plan.id(), token);
            } else {
                failPlan(plan, token, terminalError(run));
            }
            return;
        }
        recoverPlan(plan, token, nextRun(plan, Instant.now()), null,
                run.scheduledAt());
    }

    private void recoverStaleClaims() {
        List<PlanSnapshot> stale = jdbcTemplate.query("""
                SELECT id, user_id, topic, audience, tone, schedule_type, scheduled_date,
                       ai_option, knowledge_material_id, auto_publish, timezone, trigger_time,
                       trigger_times_json, topics_json, next_topic_index, sync_csdn, sync_juejin,
                       run_once, next_run_at, processing_token, status
                FROM blog_content_plan
                WHERE status = 'RUNNING' AND deleted_at IS NULL
                  AND claimed_at < TIMESTAMPADD(MICROSECOND, -( ? * 1000), CURRENT_TIMESTAMP(3))
                ORDER BY claimed_at ASC LIMIT ?
                """, this::mapPlan,
                Math.max(1L, schedulerProperties.getClaimTimeoutMs()),
                Math.max(1, schedulerProperties.getBatchSize()));
        for (PlanSnapshot plan : stale) {
            String message = plan.runOnce()
                    ? "执行进程意外中断，本次触发未完成，计划已标记失败"
                    : "执行进程意外中断，本次触发已跳过并顺延到下一周期";
            if (plan.runOnce() && hasSucceededRun(plan.id())) {
                jdbcTemplate.update("""
                        UPDATE blog_content_plan
                        SET status = 'FINISHED', next_run_at = NULL, last_run_at = ?,
                            processing_token = NULL, claimed_at = NULL, error_message = NULL
                        WHERE id = ? AND status = 'RUNNING' AND processing_token = ?
                        """, timestamp(plan.nextRunAt()), plan.id(), plan.processingToken());
                continue;
            }
            if (plan.runOnce()) {
                failPlan(plan, plan.processingToken(), message);
            } else {
                recoverPlan(plan, plan.processingToken(), nextRun(plan, Instant.now()), message);
            }
            failInFlightRuns(plan.id(), message);
            log.warn("[BlogPlan] recovered stale content plan={}", plan.id());
        }
    }

    /** Creates and asynchronously executes one user-triggered run. */
    public BlogPlanRunVO triggerManual(Long userId, Long planId) {
        requireUser(userId);
        PlanSnapshot plan = requireOwnedPlan(userId, planId);
        if ("RUNNING".equals(plan.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "计划正在执行，请稍后再试");
        }
        if (plan.runOnce()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "一次性计划不支持手动触发，将在计划时间自动执行一次");
        }
        Long runId = createManualRun(plan);
        try {
            executor.execute(() -> execute(runId, plan));
        } catch (RuntimeException exception) {
            failRun(runId, STAGE_GENERATE, rootMessage(exception));
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "计划执行队列繁忙，请稍后重试", exception);
        }
        return runReadService.detail(userId, planId, runId);
    }

    private void execute(Long runId, PlanSnapshot plan) {
        Long userId = plan.userId();
        try {
            String input = buildInput(plan);
            BlogAgentTaskCreateRequest request = new BlogAgentTaskCreateRequest();
            request.setInput(input);
            BlogAgentTask task = taskWriteService.create(userId, request);
            updateRun(runId, STATUS_GENERATING, null, null);
            markAgentTask(runId, task.getId());

            taskWriteService.run(userId, task.getId());
            updateRun(runId, STATUS_REVIEWING, null, null);
            BlogAgentTaskVO ready = taskReadService.detail(userId, task.getId());
            ComplianceResult review = review(ready.getTask());
            if (!review.pass()) {
                rejectRun(runId, plan, review.reason());
                return;
            }

            updateRun(runId, STATUS_PUBLISHING, null, null);
            BlogPostVO post = publishService.publish(userId, task.getId());
            markLocalPost(runId, post.getId());
            if (!plan.autoPublish()) {
                BlogPostStatusUpdateRequest draft = new BlogPostStatusUpdateRequest();
                draft.setStatus(DRAFT_STATUS);
                blogMutationService.updateStatus(userId, post.getId(), draft);
                finishSucceeded(runId);
                return;
            }

            // Vendor calls stay outside database transactions. Each platform records its own
            // outcome so a partial failure never rolls back the successfully published post.
            externalSyncService.sync(new ContentPlanExternalSyncService.ExternalSyncPlan(
                    runId, plan.userId(), plan.id(), plan.topic(), post.getId(),
                    plan.syncCsdn(), plan.syncJuejin()));
            finishSucceeded(runId);
        } catch (RuntimeException exception) {
            String message = limit(rootMessage(exception), 1000);
            failRun(runId, stageOf(runId), message);
            notifyUser(userId, "RUN_FAILED", "定时发文执行失败",
                    "计划《" + plan.topic() + "》本次执行失败：" + message, plan.id(), runId);
            log.warn("[BlogPlan] content-owned run={} failed: {}", runId, message);
        }
    }


    private ComplianceResult review(BlogAgentTask task) {
        if (task == null || !StringUtils.hasText(task.getContent())) {
            return new ComplianceResult(false, "文章内容为空，无法进行合规审核");
        }
        BlogAgentModelContract.Response response = modelClient.compliance(
                defaultText(task.getTitle(), ""), task.getContent());
        JsonNode payload = parsePayload(response);
        boolean pass = payload.path("pass").asBoolean(false);
        String reason = payload.path("reasons").isArray()
                ? payload.path("reasons").toString()
                : defaultText(payload.path("reasons").asText(), "合规审核未通过");
        return new ComplianceResult(pass, limit(reason, 1000));
    }

    private String buildInput(PlanSnapshot plan) {
        String topic = consumeNextTopic(plan);
        StringBuilder input = new StringBuilder(topic);
        if (plan.knowledgeMaterialId() != null) {
            MaterialView material = recitationMaterialClient.getMaterial(
                    plan.userId(), plan.knowledgeMaterialId());
            input.append("\n请优先依据以下个人知识材料进行创作；不得泄露材料之外的用户隐私：\n")
                    .append(defaultText(material.title(), ""))
                    .append("\n")
                    .append(limit(material.content(), 8000));
        }
        if (StringUtils.hasText(plan.audience())) {
            input.append("\n目标读者：").append(plan.audience());
        }
        if (StringUtils.hasText(plan.tone())) {
            input.append("\n文风语气：").append(plan.tone());
        }
        if (StringUtils.hasText(plan.aiOption())) {
            input.append("\n内容规划方向：").append(switch (plan.aiOption()) {
                case "PRACTICAL" -> "实战教程，强调步骤和可操作示例";
                case "NEWS" -> "资讯解读，突出时效性与关键信息";
                case "OPINION" -> "观点评论，给出清晰论点与论据";
                default -> "深度解析，讲清核心原理与最佳实践";
            });
        }
        List<String> previousTitles = previousPublishedTitles(plan.id());
        if (!previousTitles.isEmpty()) {
            input.append("\n本计划之前已发布过以下标题，请从全新角度撰写一篇内容不重复的文章，"
                    + "标题不要与下列标题重复，也不一定要包含主题关键词：\n")
                    .append(String.join("、", previousTitles));
        }
        return input.toString();
    }

    private String consumeNextTopic(PlanSnapshot plan) {
        return transactionTemplate.execute(status -> {
            List<QueueState> rows = jdbcTemplate.query(
                    "SELECT topics_json, next_topic_index, topic FROM blog_content_plan "
                            + "WHERE id = ? AND user_id = ? AND deleted_at IS NULL FOR UPDATE",
                    (rs, rowNum) -> new QueueState(rs.getString("topics_json"),
                            rs.getObject("next_topic_index", Integer.class), rs.getString("topic")),
                    plan.id(), plan.userId());
            if (rows.isEmpty()) {
                throw new IllegalStateException("定时发文计划不存在");
            }
            QueueState state = rows.get(0);
            List<String> topics = parseList(state.topicsJson());
            if (topics.isEmpty()) return defaultText(state.topic(), plan.topic());
            int index = state.nextTopicIndex() == null ? 0 : state.nextTopicIndex();
            String topic = topics.get(index % topics.size());
            jdbcTemplate.update("UPDATE blog_content_plan SET next_topic_index = ? WHERE id = ?",
                    index + 1, plan.id());
            return topic;
        });
    }

    private List<String> previousPublishedTitles(Long planId) {
        return jdbcTemplate.query(
                "SELECT p.title FROM blog_plan_run r JOIN blog_post p ON p.id = r.local_post_id "
                        + "WHERE r.plan_id = ? AND r.status = 'SUCCEEDED' "
                        + "ORDER BY r.finished_at DESC, r.id DESC LIMIT 10",
                (rs, rowNum) -> rs.getString("title"), planId);
    }

    private Long createManualRun(PlanSnapshot plan) {
        Instant now = Instant.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO blog_plan_run (
                        plan_id, scheduled_at, trigger_type, status,
                        csdn_status, juejin_status, processing_token, claimed_at, started_at
                    ) VALUES (?, ?, 'MANUAL', 'GENERATING', ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, plan.id());
            statement.setObject(2, java.sql.Timestamp.from(now));
            statement.setString(3, plan.syncCsdn() ? "PENDING" : "UNSET");
            statement.setString(4, plan.syncJuejin() ? "PENDING" : "UNSET");
            statement.setString(5, UUID.randomUUID().toString());
            statement.setObject(6, java.sql.Timestamp.from(now));
            statement.setObject(7, java.sql.Timestamp.from(now));
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("创建计划执行记录失败");
        return key.longValue();
    }

    private PlanSnapshot requireOwnedPlan(Long userId, Long planId) {
        List<PlanSnapshot> plans = jdbcTemplate.query(
                planSelect() + " WHERE id = ? AND user_id = ? AND deleted_at IS NULL",
                this::mapPlan, planId, userId);
        if (plans.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "定时发文计划不存在");
        }
        return plans.get(0);
    }

    private String planSelect() {
        return """
                SELECT id, user_id, topic, audience, tone, schedule_type, scheduled_date,
                       ai_option, knowledge_material_id, auto_publish, timezone, trigger_time,
                       trigger_times_json, topics_json, next_topic_index, sync_csdn, sync_juejin,
                       run_once, next_run_at, processing_token, status
                FROM blog_content_plan
                """;
    }

    private PlanSnapshot mapPlan(ResultSet rs, int rowNum) throws SQLException {
        return new PlanSnapshot(
                rs.getObject("id", Long.class),
                rs.getObject("user_id", Long.class),
                rs.getString("topic"),
                rs.getString("audience"),
                rs.getString("tone"),
                defaultText(rs.getString("schedule_type"), "DAILY"),
                rs.getDate("scheduled_date") == null
                        ? null : rs.getDate("scheduled_date").toLocalDate(),
                rs.getString("ai_option"),
                rs.getObject("knowledge_material_id", Long.class),
                toBoolean(rs, "auto_publish"),
                rs.getString("timezone"),
                rs.getString("trigger_time"),
                rs.getString("trigger_times_json"),
                rs.getString("topics_json"),
                rs.getObject("next_topic_index", Integer.class),
                toBoolean(rs, "sync_csdn"),
                toBoolean(rs, "sync_juejin"),
                toBoolean(rs, "run_once"),
                toInstant(rs, "next_run_at"),
                rs.getString("processing_token"),
                rs.getString("status"));
    }

    private Instant nextRun(PlanSnapshot plan, Instant reference) {
        return ContentPlanScheduleCalculator.nextRun(plan.scheduleType(), plan.timezone(),
                parseTriggerTimes(plan.triggerTimesJson()), plan.triggerTime(),
                plan.scheduledDate(), reference);
    }

    private void recoverPlan(PlanSnapshot plan, String token, Instant nextRun, String message) {
        recoverPlan(plan, token, nextRun, message, null);
    }

    private void recoverPlan(PlanSnapshot plan, String token, Instant nextRun, String message,
                             Instant lastRunAt) {
        jdbcTemplate.update("""
                UPDATE blog_content_plan
                SET status = 'ACTIVE', next_run_at = ?, last_run_at = COALESCE(?, last_run_at),
                    processing_token = NULL, claimed_at = NULL, error_message = ?
                WHERE id = ? AND deleted_at IS NULL AND status = 'RUNNING'
                  AND processing_token = ?
                """, timestamp(nextRun), timestamp(lastRunAt), message, plan.id(), token);
    }

    private void failPlan(PlanSnapshot plan, String token, String message) {
        jdbcTemplate.update("""
                UPDATE blog_content_plan
                SET status = 'FAILED', next_run_at = NULL, last_run_at = COALESCE(?, last_run_at),
                    processing_token = NULL, claimed_at = NULL, error_message = ?
                WHERE id = ? AND deleted_at IS NULL AND status = 'RUNNING'
                  AND processing_token = ?
                """, timestamp(plan.nextRunAt()), message, plan.id(), token);
        failInFlightRuns(plan.id(), message);
    }

    private void failInFlightRuns(Long planId, String message) {
        jdbcTemplate.update("""
                UPDATE blog_plan_run
                SET status = 'FAILED', stage_error = 'stale', error_message = ?,
                    finished_at = CURRENT_TIMESTAMP(3)
                WHERE plan_id = ? AND status IN ('GENERATING', 'REVIEWING', 'PUBLISHING')
                """, limit(message, 1000), planId);
    }

    private boolean hasSucceededRun(Long planId) {
        Number value = jdbcTemplate.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM blog_plan_run WHERE plan_id = ? AND status = 'SUCCEEDED'
                )
                """, Number.class, planId);
        return value != null && value.intValue() != 0;
    }

    private String terminalError(RunTerminal run) {
        if (StringUtils.hasText(run.errorMessage())) {
            return run.errorMessage();
        }
        if (STATUS_REVIEW_REJECTED.equals(run.status()) && StringUtils.hasText(run.reviewReason())) {
            return run.reviewReason();
        }
        return "计划执行失败";
    }

    private java.sql.Timestamp timestamp(Instant value) {
        return java.sql.Timestamp.from(value == null ? Instant.now() : value);
    }

    private void updateRun(Long runId, String status, String stage, String message) {
        jdbcTemplate.update("UPDATE blog_plan_run SET status = ?, stage_error = ?, error_message = ? "
                        + "WHERE id = ? AND status NOT IN ('SUCCEEDED', 'FAILED', 'REVIEW_REJECTED')",
                status, stage, message, runId);
    }

    private String stageOf(Long runId) {
        return jdbcTemplate.query("SELECT status FROM blog_plan_run WHERE id = ?",
                        (rs, rowNum) -> rs.getString("status"), runId).stream()
                .findFirst()
                .map(status -> switch (status) {
                    case STATUS_REVIEWING -> STAGE_REVIEW;
                    case STATUS_PUBLISHING -> STAGE_PUBLISH;
                    default -> STAGE_GENERATE;
                })
                .orElse(STAGE_GENERATE);
    }

    private void markAgentTask(Long runId, Long taskId) {
        jdbcTemplate.update("UPDATE blog_plan_run SET agent_task_id = ? WHERE id = ?", taskId, runId);
    }

    private void markLocalPost(Long runId, Long postId) {
        jdbcTemplate.update("UPDATE blog_plan_run SET local_post_id = ? WHERE id = ?", postId, runId);
    }

    private void finishSucceeded(Long runId) {
        jdbcTemplate.update("UPDATE blog_plan_run SET status = ?, review_pass = 1, finished_at = ? WHERE id = ?",
                STATUS_SUCCEEDED, java.sql.Timestamp.from(Instant.now()), runId);
    }

    private void rejectRun(Long runId, PlanSnapshot plan, String reason) {
        jdbcTemplate.update("UPDATE blog_plan_run SET status = ?, review_pass = 0, review_reason = ?, "
                        + "finished_at = ? WHERE id = ?",
                STATUS_REVIEW_REJECTED, limit(reason, 1000), java.sql.Timestamp.from(Instant.now()), runId);
        notifyUser(plan.userId(), "REVIEW_REJECTED", "文章未通过合规审核",
                "计划《" + plan.topic() + "》本次生成的内容未通过合规审核：" + limit(reason, 300),
                plan.id(), runId);
    }

    private void failRun(Long runId, String stage, String message) {
        jdbcTemplate.update("UPDATE blog_plan_run SET status = ?, stage_error = ?, error_message = ?, "
                        + "finished_at = ? WHERE id = ? AND status NOT IN ('SUCCEEDED', 'REVIEW_REJECTED')",
                STATUS_FAILED, stage, limit(message, 1000), java.sql.Timestamp.from(Instant.now()), runId);
    }

    private void notifyUser(Long userId, String type, String title, String message,
                            Long planId, Long runId) {
        if (userId == null || userId <= 0) return;
        String safeMessage = limit(message, 1000);
        jdbcTemplate.update("INSERT INTO blog_notification "
                        + "(user_id, type, title, message, plan_id, run_id, is_read) VALUES (?, ?, ?, ?, ?, ?, 0)",
                userId, type, title, safeMessage, planId, runId);
        notificationStreamService.push(userId, type, title, safeMessage, planId, runId);
    }

    private JsonNode parsePayload(BlogAgentModelContract.Response response) {
        try {
            if (response == null || response.payloadJson() == null || response.payloadJson().isBlank()) {
                throw new IllegalStateException("合规审核服务没有返回结果");
            }
            return objectMapper.readTree(response.payloadJson());
        } catch (Exception exception) {
            throw new IllegalStateException("合规审核结果不是有效 JSON", exception);
        }
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("计划主题队列数据损坏", exception);
        }
    }

    private Boolean toBoolean(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) return false;
        return value instanceof Boolean booleanValue
                ? booleanValue : ((Number) value).intValue() != 0;
    }

    private Instant toInstant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private List<String> parseTriggerTimes(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("计划触发时间数据损坏", exception);
        }
    }

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return defaultText(current.getMessage(), current.getClass().getSimpleName());
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String limit(String value, int max) {
        String safe = value == null ? "" : value;
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private record PlanSnapshot(Long id, Long userId, String topic, String audience, String tone,
                                String scheduleType, java.time.LocalDate scheduledDate,
                                String aiOption, Long knowledgeMaterialId, boolean autoPublish,
                                String timezone, String triggerTime, String triggerTimesJson,
                                String topicsJson, Integer nextTopicIndex, boolean syncCsdn,
                                boolean syncJuejin, boolean runOnce, Instant nextRunAt,
                                String processingToken, String status) {
    }

    private record QueueState(String topicsJson, Integer nextTopicIndex, String topic) {
    }

    private record ComplianceResult(boolean pass, String reason) {
    }

    private record ScheduledClaim(PlanSnapshot plan, Long runId, boolean created,
                                  String processingToken) {
    }

    private record RunTerminal(String status, String errorMessage, String reviewReason,
                               Instant scheduledAt) {
    }
}
