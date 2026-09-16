package com.xander.lab.automation.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xander.lab.automation.api.BlogContentPlanAiGenerateRequest;
import com.xander.lab.automation.api.BlogContentPlanCreateRequest;
import com.xander.lab.automation.api.BlogContentPlanTopicsGenerateRequest;
import com.xander.lab.automation.api.BlogContentPlanUpdateRequest;
import com.xander.lab.automation.api.BlogContentPlanVO;
import com.xander.lab.automation.api.BlogContentPlansPage;
import com.xander.lab.automation.api.BlogPlanRunVO;
import com.xander.lab.automation.api.BlogPlanRunsPage;
import com.xander.lab.automation.domain.BlogContentPlan;
import com.xander.lab.automation.domain.BlogContentPlanScheduleCalculator;
import com.xander.lab.automation.domain.BlogPlanRun;
import com.xander.lab.automation.domain.EmailReminderScheduleCalculator;
import com.xander.lab.automation.infrastructure.BlogContentPlanMapper;
import com.xander.lab.automation.infrastructure.BlogPlanRunMapper;
import com.xander.lab.blogagent.application.BlogAgentService;
import com.xander.lab.integration.mcp.csdn.application.CsdnCredentialService;
import com.xander.lab.integration.mcp.juejin.application.JuejinCredentialService;
import com.xander.lab.recitation.application.RecitationService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** C-end use cases for daily content plans: CRUD, lifecycle transitions,
 * manual test trigger, run history, and next-fire preview. */
@Service
@RequiredArgsConstructor
public class BlogContentPlanService {

    private static final String UNAUTHORIZED_MESSAGE = "未登录或登录已过期";

    private static final Set<String> PLAN_STATUSES = Set.of(
            BlogContentPlan.STATUS_ACTIVE,
            BlogContentPlan.STATUS_RUNNING,
            BlogContentPlan.STATUS_PAUSED,
            BlogContentPlan.STATUS_CANCELLED,
            BlogContentPlan.STATUS_FINISHED,
            BlogContentPlan.STATUS_FAILED);

    private final BlogContentPlanMapper planMapper;
    private final BlogPlanRunMapper runMapper;
    private final BlogPlanRunService runService;
    private final CsdnCredentialService csdnCredentialService;
    private final JuejinCredentialService juejinCredentialService;
    private final BlogAgentService agentService;
    private final RecitationService recitationService;

    @Value("${blog-content-plan.max-active-per-user:50}")
    private long maxActivePerUser;

    @Value("${blog-content-plan.max-created-per-day:20}")
    private long maxCreatedPerDay;

    @Transactional
    public BlogContentPlanVO create(Long userId, BlogContentPlanCreateRequest request) {
        requireUser(userId);
        if (planMapper.lockUserForPlanCreation(userId) == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, UNAUTHORIZED_MESSAGE);
        }

        String clientRequestId = normalizeClientRequestId(request.getClientRequestId());
        if (clientRequestId != null) {
            BlogContentPlan existing = findPlanByClientRequestId(userId, clientRequestId);
            if (existing != null) {
                if (existing.getDeletedAt() != null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "该幂等请求标识已被删除计划使用，请生成新的标识");
                }
                return toVO(existing, userId);
            }
        }

        String topic = request.getTopic().strip();
        ZoneId zone = EmailReminderScheduleCalculator.resolveZone(request.getTimezone());
        List<LocalTime> times = resolveTriggerTimes(request.getTriggerTimes(), request.getTriggerTime());
        String scheduleType = request.getScheduleType() == null || request.getScheduleType().isBlank()
                ? BlogContentPlan.SCHEDULE_DAILY : request.getScheduleType();

        long activeCount = planMapper.countActivePlans(userId);
        if (activeCount >= maxActivePerUser) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "定时发文计划数量已达到上限，请先暂停或删除部分计划");
        }

        long createdPastDay = planMapper.countCreatedInPastDay(userId);
        if (createdPastDay >= maxCreatedPerDay) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "过去 24 小时创建的定时发文计划已达到上限");
        }

        Instant now = Instant.now();
        Instant nextRun = BlogContentPlanScheduleCalculator.firstRun(
                scheduleType, zone, times, request.getScheduledDate(), now);
        List<String> topics = normalizeTopics(request.getTopics());
        if (request.getKnowledgeMaterialId() != null) {
            recitationService.getMaterial(userId, request.getKnowledgeMaterialId());
        }

        BlogContentPlan plan = new BlogContentPlan();
        plan.setUserId(userId);
        plan.setClientRequestId(clientRequestId);
        plan.setTopic(topic);
        plan.setAudience(strip(request.getAudience()));
        plan.setTone(strip(request.getTone()));
        plan.setScheduleType(scheduleType);
        plan.setScheduledDate(request.getScheduledDate());
        plan.setAiOption(request.getAiOption() == null ? "DEEP" : request.getAiOption());
        plan.setKnowledgeMaterialId(request.getKnowledgeMaterialId());
        plan.setAutoPublish(request.getAutoPublish() == null || request.getAutoPublish());
        plan.setTimezone(zone.getId());
        plan.setTriggerTime(times.get(0).format(DateTimeFormatter.ofPattern("HH:mm")));
        plan.setTriggerTimesJson(BlogContentPlan.toJson(times.stream()
                .map(t -> t.format(DateTimeFormatter.ofPattern("HH:mm"))).toList()));
        plan.setTopicsJson(BlogContentPlan.toJson(topics));
        plan.setNextTopicIndex(0);
        plan.setSyncCsdn(request.getSyncCsdn() == null ? Boolean.FALSE : request.getSyncCsdn());
        plan.setSyncJuejin(request.getSyncJuejin() == null ? Boolean.FALSE : request.getSyncJuejin());
        plan.setRunOnce(BlogContentPlan.SCHEDULE_ONCE.equals(scheduleType));
        plan.setNextRunAt(nextRun);
        plan.setStatus(BlogContentPlan.STATUS_ACTIVE);
        planMapper.insert(plan);
        return toVO(planMapper.selectById(plan.getId()), userId);
    }

    /** Finds an existing create result by the caller's stable idempotency key. */
    @Transactional(readOnly = true)
    public BlogContentPlanVO findByClientRequestId(Long userId, String clientRequestId) {
        requireUser(userId);
        String normalized = normalizeClientRequestId(clientRequestId);
        if (normalized == null) {
            return null;
        }
        BlogContentPlan plan = findPlanByClientRequestId(userId, normalized);
        return plan == null || plan.getDeletedAt() != null ? null : toVO(plan, userId);
    }

    /** AI 生成计划: refines the seed into one topic per day (model call, kept
     * outside any transaction) and then creates that many one-shot plans firing
     * tomorrow + i days at the same local time. Each plan runs exactly once. */
    public List<BlogContentPlanVO> aiGenerate(Long userId, BlogContentPlanAiGenerateRequest request) {
        requireUser(userId);
        String seed = request.getTopic().strip();
        int days = request.getDays() == null ? 7 : request.getDays();
        ZoneId zone = EmailReminderScheduleCalculator.resolveZone(request.getTimezone());
        LocalTime time = request.getTime() == null || request.getTime().isBlank()
                ? LocalTime.of(9, 0)
                : EmailReminderScheduleCalculator.parseSendTime(request.getTime());

        List<String> excludeTitles = new ArrayList<>();
        excludeTitles.add(seed);
        planMapper.selectList(ownedWrapper(userId)).forEach(plan -> excludeTitles.addAll(plan.topics()));
        List<String> topics = agentService.generateTopicSeries(
                userId, seed, request.getAudience(), request.getTone(), days, excludeTitles);
        if (topics.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "AI 主题生成失败，请稍后重试");
        }
        return createOneShotPlans(userId, request, zone, time, topics);
    }

    /** Inserts one one-shot plan per generated topic, tomorrow + i days at the
     * same local `time`. Quota is checked against the whole batch up front. */
    @Transactional
    public List<BlogContentPlanVO> createOneShotPlans(Long userId, BlogContentPlanAiGenerateRequest request,
                                                      ZoneId zone, LocalTime time, List<String> topics) {
        if (planMapper.lockUserForPlanCreation(userId) == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, UNAUTHORIZED_MESSAGE);
        }
        int days = topics.size();
        long activeCount = planMapper.countActivePlans(userId);
        if (activeCount + days > maxActivePerUser) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "定时发文计划数量已达到上限，请先暂停或删除部分计划");
        }
        long createdPastDay = planMapper.countCreatedInPastDay(userId);
        if (createdPastDay + days > maxCreatedPerDay) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "过去 24 小时创建的定时发文计划已达到上限");
        }

        LocalDate startDate = LocalDate.now(zone).plusDays(1);
        String timeText = time.format(DateTimeFormatter.ofPattern("HH:mm"));
        List<BlogContentPlanVO> created = new ArrayList<>();
        for (int i = 0; i < topics.size(); i++) {
            BlogContentPlan plan = new BlogContentPlan();
            plan.setUserId(userId);
            plan.setTopic(topics.get(i));
            plan.setAudience(strip(request.getAudience()));
            plan.setTone(strip(request.getTone()));
            plan.setTimezone(zone.getId());
            plan.setTriggerTime(timeText);
            plan.setTriggerTimesJson(BlogContentPlan.toJson(List.of(timeText)));
            plan.setTopicsJson(BlogContentPlan.toJson(List.of()));
            plan.setNextTopicIndex(0);
            plan.setRunOnce(true);
            plan.setScheduleType(BlogContentPlan.SCHEDULE_ONCE);
            plan.setScheduledDate(startDate.plusDays(i));
            plan.setAiOption("DEEP");
            plan.setAutoPublish(true);
            plan.setSyncCsdn(request.getSyncCsdn() == null ? Boolean.FALSE : request.getSyncCsdn());
            plan.setSyncJuejin(request.getSyncJuejin() == null ? Boolean.FALSE : request.getSyncJuejin());
            plan.setNextRunAt(ZonedDateTime.of(startDate.plusDays(i), time, zone).toInstant());
            plan.setStatus(BlogContentPlan.STATUS_ACTIVE);
            planMapper.insert(plan);
            created.add(toVO(planMapper.selectById(plan.getId()), userId));
        }
        return created;
    }

    @Transactional(readOnly = true)
    public BlogContentPlansPage list(Long userId, long page, long size) {
        requireUser(userId);
        long safeSize = Math.min(Math.max(size, 1), 50);
        Page<BlogContentPlan> result = planMapper.selectPage(
                new Page<>(Math.max(page, 1), safeSize),
                ownedWrapper(userId).orderByDesc(BlogContentPlan::getCreatedAt)
                        .orderByDesc(BlogContentPlan::getId));
        return BlogContentPlansPage.builder()
                .records(result.getRecords().stream().map(plan -> toVO(plan, userId)).toList())
                .total(result.getTotal())
                .page(result.getCurrent())
                .size(result.getSize())
                .build();
    }

    /** 当前用户全部计划（不分页），status 非空时按状态过滤（如 ACTIVE / FAILED），
     * ALL 或空表示全部；供智能体一次调用拿全量，无需逐页查询。 */
    @Transactional(readOnly = true)
    public List<BlogContentPlanVO> listAll(Long userId, String status) {
        requireUser(userId);
        String normalized = normalizeListStatus(status);
        LambdaQueryWrapper<BlogContentPlan> wrapper = ownedWrapper(userId);
        if (normalized != null) {
            wrapper.eq(BlogContentPlan::getStatus, normalized);
        }
        wrapper.orderByDesc(BlogContentPlan::getCreatedAt)
                .orderByDesc(BlogContentPlan::getId);
        return planMapper.selectList(wrapper).stream()
                .map(plan -> toVO(plan, userId))
                .toList();
    }

    private String normalizeListStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.strip().toUpperCase(Locale.ROOT);
        if ("ALL".equals(normalized)) {
            return null;
        }
        if (!PLAN_STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的计划状态筛选");
        }
        return normalized;
    }

    @Transactional(readOnly = true)
    public BlogContentPlanVO detail(Long userId, Long planId) {
        requireUser(userId);
        return toVO(requireOwnedPlan(planId, userId), userId);
    }

    @Transactional
    public BlogContentPlanVO update(Long userId, Long planId, BlogContentPlanUpdateRequest request) {
        requireUser(userId);
        BlogContentPlan plan = requireOwnedPlan(planId, userId);
        if (BlogContentPlan.STATUS_RUNNING.equals(plan.getStatus())
                || BlogContentPlan.STATUS_CANCELLED.equals(plan.getStatus())
                || BlogContentPlan.STATUS_FINISHED.equals(plan.getStatus())
                || BlogContentPlan.STATUS_FAILED.equals(plan.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "计划执行中、已取消、已完结或已失败，暂不能修改");
        }

        String topic = request.getTopic();
        String timezone = request.getTimezone();
        String triggerTime = request.getTriggerTime();
        boolean scheduleChanged = false;
        if (topic != null && !topic.isBlank()) {
            plan.setTopic(topic.strip());
        }
        if (request.getSyncCsdn() != null) {
            plan.setSyncCsdn(request.getSyncCsdn());
        }
        if (request.getSyncJuejin() != null) {
            plan.setSyncJuejin(request.getSyncJuejin());
        }
        if (request.getAudience() != null) {
            plan.setAudience(strip(request.getAudience()));
        }
        if (request.getTone() != null) {
            plan.setTone(strip(request.getTone()));
        }
        if (request.getAiOption() != null) {
            plan.setAiOption(request.getAiOption());
        }
        if (request.getKnowledgeMaterialId() != null) {
            recitationService.getMaterial(userId, request.getKnowledgeMaterialId());
            plan.setKnowledgeMaterialId(request.getKnowledgeMaterialId());
        }
        if (request.getAutoPublish() != null) {
            plan.setAutoPublish(request.getAutoPublish());
        }
        if (request.getScheduleType() != null) {
            plan.setScheduleType(request.getScheduleType());
            plan.setRunOnce(BlogContentPlan.SCHEDULE_ONCE.equals(request.getScheduleType()));
            scheduleChanged = true;
        }
        if (request.getScheduledDate() != null) {
            plan.setScheduledDate(request.getScheduledDate());
            scheduleChanged = true;
        }
        if (request.getTopics() != null) {
            plan.setTopicsJson(BlogContentPlan.toJson(normalizeTopics(request.getTopics())));
            plan.setNextTopicIndex(0);
        }

        if (request.getTriggerTimes() != null) {
            List<LocalTime> times = EmailReminderScheduleCalculator.parseSendTimes(request.getTriggerTimes());
            String timesJson = BlogContentPlan.toJson(times.stream()
                    .map(t -> t.format(DateTimeFormatter.ofPattern("HH:mm"))).toList());
            if (!timesJson.equals(plan.getTriggerTimesJson())) {
                plan.setTriggerTimesJson(timesJson);
                plan.setTriggerTime(times.get(0).format(DateTimeFormatter.ofPattern("HH:mm")));
                scheduleChanged = true;
            }
        } else if (triggerTime != null && !triggerTime.isBlank()
                && !triggerTime.equals(plan.getTriggerTime())) {
            LocalTime time = EmailReminderScheduleCalculator.parseSendTime(triggerTime);
            plan.setTriggerTime(time.format(DateTimeFormatter.ofPattern("HH:mm")));
            plan.setTriggerTimesJson(null);
            scheduleChanged = true;
        }
        if (timezone != null && !timezone.isBlank()) {
            ZoneId zone = EmailReminderScheduleCalculator.resolveZone(timezone);
            if (!zone.getId().equals(plan.getTimezone())) {
                plan.setTimezone(zone.getId());
                scheduleChanged = true;
            }
        }
        // When the schedule changed, recompute the next fire from now so an
        // edit can never leave the plan stuck in the past.
        if (scheduleChanged) {
            ZoneId zone = EmailReminderScheduleCalculator.resolveZone(plan.getTimezone());
            List<LocalTime> times = EmailReminderScheduleCalculator.parseSendTimes(plan.allTriggerTimes());
            plan.setNextRunAt(BlogContentPlanScheduleCalculator.firstRun(
                    plan.getScheduleType(), zone, times, plan.getScheduledDate(), Instant.now()));
        }
        planMapper.updateById(plan);
        return toVO(planMapper.selectById(plan.getId()), userId);
    }

    @Transactional
    public BlogContentPlanVO updateStatus(Long userId, Long planId, String action) {
        requireUser(userId);
        BlogContentPlan plan = requireOwnedPlan(planId, userId);
        String normalized = action.strip().toUpperCase(Locale.ROOT);

        int updated;
        switch (normalized) {
            case "PAUSED" -> {
                if (!BlogContentPlan.STATUS_ACTIVE.equals(plan.getStatus())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "当前状态无法暂停");
                }
                updated = planMapper.pauseOwnedPlan(planId, userId);
            }
            case "RESUME" -> {
                if (!BlogContentPlan.STATUS_PAUSED.equals(plan.getStatus())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "当前状态无法恢复");
                }
                Instant now = Instant.now();
                if (Boolean.TRUE.equals(plan.getRunOnce())
                        || (plan.getNextRunAt() != null && plan.getNextRunAt().isAfter(now))) {
                    // One-shot plans keep their fixed trigger instant; if it
                    // already passed while paused, the poller fires it once
                    // on resume and the plan then auto-finishes.
                    updated = planMapper.resumeOwnedPlan(planId, userId);
                } else {
                    ZoneId zone = EmailReminderScheduleCalculator.resolveZone(plan.getTimezone());
                    List<LocalTime> times = EmailReminderScheduleCalculator.parseSendTimes(plan.allTriggerTimes());
                    Instant next = EmailReminderScheduleCalculator.resolveNextDailyAfter(zone, times, now);
                    updated = planMapper.resumeOwnedPlanAt(planId, userId, next);
                }
            }
            case "CANCELLED" -> updated = planMapper.cancelOwnedPlan(planId, userId);
            default -> throw new IllegalArgumentException("计划操作只支持 PAUSED / RESUME / CANCELLED");
        }

        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "计划状态已变化，请刷新后重试");
        }
        return toVO(requireOwnedPlan(planId, userId), userId);
    }

    @Transactional
    public void delete(Long userId, Long planId) {
        requireUser(userId);
        BlogContentPlan plan = requireOwnedPlan(planId, userId);
        if (BlogContentPlan.STATUS_RUNNING.equals(plan.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "计划正在执行，暂时不能删除");
        }
        if (planMapper.deleteOwnedPlanUnlessRunning(planId, userId) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "计划状态已变化，请刷新后重试");
        }
    }

    /** Immediately creates and enqueues a manual run for testing. */
    @Transactional
    public BlogPlanRun trigger(Long userId, Long planId) {
        requireUser(userId);
        BlogContentPlan plan = requireOwnedPlan(planId, userId);
        if (BlogContentPlan.STATUS_RUNNING.equals(plan.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "计划正在执行，请稍后再试");
        }
        if (Boolean.TRUE.equals(plan.getRunOnce())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "一次性计划不支持手动触发，将在计划时间自动执行一次");
        }
        return runService.createManualRun(plan);
    }

    @Transactional(readOnly = true)
    public BlogPlanRunsPage listRuns(Long userId, Long planId, long page, long size) {
        requireUser(userId);
        requireOwnedPlan(planId, userId);
        long safeSize = Math.min(Math.max(size, 1), 50);
        Page<BlogPlanRun> result = runMapper.selectPage(
                new Page<>(Math.max(page, 1), safeSize),
                new LambdaQueryWrapper<BlogPlanRun>()
                        .eq(BlogPlanRun::getPlanId, planId)
                        .orderByDesc(BlogPlanRun::getCreatedAt)
                        .orderByDesc(BlogPlanRun::getId));
        return BlogPlanRunsPage.builder()
                .records(result.getRecords().stream().map(runService::toRunVO).toList())
                .total(result.getTotal())
                .page(result.getCurrent())
                .size(result.getSize())
                .build();
    }

    @Transactional(readOnly = true)
    public BlogPlanRunVO runDetail(Long userId, Long planId, Long runId) {
        requireUser(userId);
        requireOwnedPlan(planId, userId);
        BlogPlanRun run = runMapper.selectById(runId);
        if (run == null || !run.getPlanId().equals(planId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "计划执行记录不存在");
        }
        return runService.toRunVO(run);
    }

    /** Refines a seed topic into a deduplicated topic series for a plan's
     * multi-day queue. Excludes the seed, the requester's already-published
     * posts, and existing queue topics so generated titles stay fresh. */
    /*
     * ⚠️ 刻意【不加】事务注解，尤其不能加只读事务注解（readOnly = true）。
     *
     * 本方法自己只读，但它调用的 agentService.generateTopicSeries 会做两件
     * 只读事务不该容纳的事：外部模型 HTTP 调用（BlogAgentService L126），
     * 以及 billingService.chargeTokens 写库（L142）。
     *
     * readOnly 在 MySQL 上只是给驱动的提示、不是强制约束，所以「只读事务里发生写」
     * 不会报任何错 —— 后果是模型调用慢时一个只读事务会持有连接数秒到数十秒
     * （连接池耗尽的经典成因），而且没有任何功能性症状。
     *
     * 去掉事务后：requireUser / selectList 各自是独立单语句（本就无需事务），
     * 外部调用彻底在事务之外，chargeTokens 拿到自己的事务。这正是
     * backend-norms 的「网络调用必须在事务之外」。
     */
    public List<String> generateTopics(Long userId, BlogContentPlanTopicsGenerateRequest request) {
        requireUser(userId);
        String seed = request.getTopic().strip();
        List<String> excludeTitles = new ArrayList<>();
        excludeTitles.add(seed);
        planMapper.selectList(ownedWrapper(userId)).forEach(plan -> excludeTitles.addAll(plan.topics()));
        return agentService.generateTopicSeries(
                userId, seed, request.getAudience(), request.getTone(),
                request.getCount(), excludeTitles);
    }

    @Transactional(readOnly = true)
    public Instant nextRunAfter(Long userId, Long planId, long count) {
        requireUser(userId);
        BlogContentPlan plan = requireOwnedPlan(planId, userId);
        Instant cursor = Instant.now();
        for (long i = 0; i < Math.max(1, count); i++) {
            cursor = BlogContentPlanScheduleCalculator.nextRun(plan, cursor);
        }
        return cursor;
    }

    private LambdaQueryWrapper<BlogContentPlan> ownedWrapper(Long userId) {
        return new LambdaQueryWrapper<BlogContentPlan>()
                .eq(BlogContentPlan::getUserId, userId)
                .isNull(BlogContentPlan::getDeletedAt);
    }

    private BlogContentPlan requireOwnedPlan(Long planId, Long userId) {
        BlogContentPlan plan = planMapper.selectOne(ownedWrapper(userId).eq(BlogContentPlan::getId, planId));
        if (plan == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "定时发文计划不存在");
        }
        return plan;
    }

    private BlogContentPlan findPlanByClientRequestId(Long userId, String clientRequestId) {
        return planMapper.selectOne(new LambdaQueryWrapper<BlogContentPlan>()
                .eq(BlogContentPlan::getUserId, userId)
                .eq(BlogContentPlan::getClientRequestId, clientRequestId));
    }

    private String normalizeClientRequestId(String clientRequestId) {
        if (clientRequestId == null || clientRequestId.isBlank()) {
            return null;
        }
        return clientRequestId.strip();
    }

    private BlogContentPlanVO toVO(BlogContentPlan plan, Long userId) {
        if (plan == null) return null;
        return BlogContentPlanVO.builder()
                .id(plan.getId())
                .topic(plan.getTopic())
                .triggerTimes(plan.allTriggerTimes())
                .topics(plan.topics())
                .nextTopicIndex(plan.getNextTopicIndex() == null ? 0 : plan.getNextTopicIndex())
                .audience(plan.getAudience())
                .tone(plan.getTone())
                .scheduleType(plan.getScheduleType() == null ? BlogContentPlan.SCHEDULE_DAILY : plan.getScheduleType())
                .scheduledDate(plan.getScheduledDate())
                .aiOption(plan.getAiOption() == null ? "DEEP" : plan.getAiOption())
                .knowledgeMaterialId(plan.getKnowledgeMaterialId())
                .autoPublish(plan.getAutoPublish() == null || plan.getAutoPublish())
                .timezone(plan.getTimezone())
                .triggerTime(plan.getTriggerTime())
                .syncCsdn(plan.getSyncCsdn())
                .syncJuejin(plan.getSyncJuejin())
                .runOnce(plan.getRunOnce())
                .nextRunAt(plan.getNextRunAt())
                .lastRunAt(plan.getLastRunAt())
                .status(plan.getStatus())
                .errorMessage(plan.getErrorMessage())
                .createdAt(plan.getCreatedAt())
                .updatedAt(plan.getUpdatedAt())
                .csdnAuthorized(csdnCredentialService.isAuthorized(userId))
                .juejinAuthorized(juejinCredentialService.isAuthorized(userId))
                .build();
    }

    private List<LocalTime> resolveTriggerTimes(List<String> triggerTimes, String triggerTime) {
        if (triggerTimes != null && !triggerTimes.isEmpty()) {
            return EmailReminderScheduleCalculator.parseSendTimes(triggerTimes);
        }
        if (triggerTime == null || triggerTime.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请设置每日触发时间");
        }
        return List.of(EmailReminderScheduleCalculator.parseSendTime(triggerTime));
    }

    private List<String> normalizeTopics(List<String> topics) {
        if (topics == null) return List.of();
        return topics.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::strip)
                .distinct()
                .limit(30)
                .toList();
    }

    private String strip(String value) {
        if (value == null) return "";
        String stripped = value.strip();
        return stripped.length() > 120 ? stripped.substring(0, 120) : stripped;
    }

    private void requireUser(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, UNAUTHORIZED_MESSAGE);
        }
    }
}
