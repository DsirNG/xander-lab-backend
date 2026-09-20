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
import com.xander.lab.automation.spi.ContentPlanDraft;
import com.xander.lab.automation.spi.ContentPlanEdit;
import com.xander.lab.automation.spi.ContentPlanPort;
import com.xander.lab.automation.spi.ContentPlanView;
import com.xander.lab.blogagent.application.BlogAgentService;
import com.xander.lab.integration.api.CsdnCredentialPort;
import com.xander.lab.integration.api.JuejinCredentialPort;
import com.xander.lab.recitation.spi.RecitationMaterialPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.function.BooleanSupplier;

/** C-end use cases for daily content plans: CRUD, lifecycle transitions,
 * manual test trigger, run history, and next-fire preview. */
/**
 * 定时发文计划的领域服务；同时是 {@link ContentPlanPort} 在 automation 域这一侧的实现。
 *
 * <h2>为什么同一个能力有两套公开方法</h2>
 * <p>本类对<b>自己的控制器</b>公开 15 个方法，返回前端页面要的全字段
 * {@code BlogContentPlanVO}（24 个字段，含 {@code csdnAuthorized} / {@code juejinAuthorized}
 * 这类要现场查凭据的派生字段）。智能体的三个工具只要 6 个操作、11 个字段，
 * 通过 {@link ContentPlanPort} 拿 {@link ContentPlanView}。</p>
 * <p>两者不能只靠返回类型重载（Java 不允许），所以按本仓库既有的做法：
 * <b>端口方法拿规范名</b>（{@code updateStatus}），<b>同域的富方法加后缀说明它服务谁</b>
 * （{@code updateStatusForView}）。</p>
 *
 * <h2>为什么端口方法是转调 + 换算，而不是把实现体搬过来</h2>
 * <p>实现体一行没动：端口方法调同域的富方法，再把 VO 收窄成 {@link ContentPlanView}
 * （读方法也走 {@code toVO}，为的是让"往读模型落字段"只有一处 —— 见 {@code toView} 上的注释）。
 * 多一次内存里的对象构造，换来的是"这次抽取不改变任何行为"可以被直接看出来。</p>
 *
 * <h2>⚠️ 两个派生字段的失败语义：fail-soft</h2>
 * <p>{@code toVO} 会调 {@link CsdnCredentialPort#isAuthorized} /
 * {@link JuejinCredentialPort#isAuthorized} 去填 {@code csdnAuthorized} /
 * {@code juejinAuthorized}（{@link ContentPlanView} 里没有这两个字段，只有前端 VO 有）。
 * 它们是<b>纯补充信息</b>，所以它们的失败<b>不得</b>改变主流程的成功/失败语义：
 * 查不出来就按"不可用"处理、留下一条 WARN，请求照常成功。</p>
 *
 * <p>判据是凭证在<b>这条路径里的语义角色</b>，不是"它叫凭证"：</p>
 * <ul>
 *   <li>这两个字段<b>不参与</b>鉴权（那是 {@code requireUser}）、租户隔离
 *       （那是 {@code requireOwnedPlan} / {@code ownedWrapper}）、执行身份判定，
 *       也<b>不决定</b>是否允许操作 ⇒ 可以 fail-soft。</li>
 *   <li>若哪天有派生字段被用来<b>决定是否允许操作</b>，它必须 fail-closed，不能吞。</li>
 * </ul>
 *
 * <p>⚠️ <b>容错的范围必须只包住凭证查询本身。</b>用的是只有<b>一个语句</b>的 try 块
 * （见 {@link #credentialAuthorizedOrFalse}）：主流程的失败（{@code UNAUTHORIZED} /
 * {@code NOT_FOUND} / {@code CONFLICT} / 非法 action 的 {@link IllegalArgumentException}）
 * 一律照抛。<b>不要</b>把这个 try 扩大到包住方法体 —— 那会让"计划不存在"变成一个静默的成功。
 * 钉住这条的是
 * {@code BlogContentPlanServiceTest#theCredentialFailSoftDoesNotSwallowTheMainFlowFailures}。</p>
 *
 * <p>⚠️ <b>成本没变：这条路径仍然做两次凭证查询。</b>改的是"它们能不能把请求带崩"，
 * 不是"要不要做"。{@code list} / {@code listAll} 是<b>逐行</b>调 {@code toVO}，
 * 所以是 2N 次查询；而这两个字段在本仓与前端仓里<b>一个消费方都没有</b>（前端走的是
 * MCP 凭证状态接口）。删掉字段是另一刀 —— 那会改线上 JSON，不在这次改动里。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlogContentPlanService implements ContentPlanPort {

    private static final String UNAUTHORIZED_MESSAGE = "未登录或登录已过期";

    /**
     * 派生字段查询失败时的<b>固定日志标记</b>，同时是将来那条指标的名字。
     *
     * <p>本仓<b>没有</b> Micrometer / Actuator（{@code core-host-lifecycle.md} 把指标列为
     * Wave 2 的事），所以这条不变式今天只能由日志承载。标记取的就是指标名本身、逐字相同：
     * 将来接入 Micrometer 时是"加一个同名 Counter"，不是"再想一个名字"，
     * 基于日志的告警规则也不必改。</p>
     */
    private static final String VO_CREDENTIAL_ENRICHMENT_FAILURE = "blog_plan_vo_credential_enrichment_failure";

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
    private final CsdnCredentialPort csdnCredentialPort;
    private final JuejinCredentialPort juejinCredentialPort;
    private final BlogAgentService agentService;
    private final RecitationMaterialPort materialPort;

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
            materialPort.getMaterial(userId, request.getKnowledgeMaterialId());
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

    /**
     * {@inheritDoc}
     *
     * <p>把契约草稿装回域请求体后转调上面那个方法，再把 VO 收窄。校验、限流、时区解析、
     * 幂等全在那一个实现体里，只有一份 —— 复制一份到这里意味着以后改限流规则要改两处，
     * 而且不会有任何编译错误提醒。</p>
     */
    @Override
    @Transactional
    public ContentPlanView create(Long userId, ContentPlanDraft draft) {
        return toView(create(userId, toCreateRequest(draft)));
    }

    /**
     * {@inheritDoc}
     *
     * <p>返回类型从 {@code BlogContentPlanVO} 换成契约读模型：本方法的<b>唯一</b>调用方是
     * {@code SchedulePlanTool} 的幂等确认路径，它只读几个字段。</p>
     */
    @Override
    @Transactional(readOnly = true)
    public ContentPlanView findByClientRequestId(Long userId, String clientRequestId) {
        requireUser(userId);
        String normalized = normalizeClientRequestId(clientRequestId);
        if (normalized == null) {
            return null;
        }
        BlogContentPlan plan = findPlanByClientRequestId(userId, normalized);
        return plan == null || plan.getDeletedAt() != null ? null : toView(toVO(plan, userId));
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
    @Override
    @Transactional(readOnly = true)
    public List<ContentPlanView> listAll(Long userId, String status) {
        requireUser(userId);
        String normalized = normalizeListStatus(status);
        LambdaQueryWrapper<BlogContentPlan> wrapper = ownedWrapper(userId);
        if (normalized != null) {
            wrapper.eq(BlogContentPlan::getStatus, normalized);
        }
        wrapper.orderByDesc(BlogContentPlan::getCreatedAt)
                .orderByDesc(BlogContentPlan::getId);
        return planMapper.selectList(wrapper).stream()
                .map(plan -> toView(toVO(plan, userId)))
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
            materialPort.getMaterial(userId, request.getKnowledgeMaterialId());
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

    /**
     * 改状态并返回<b>前端要的全字段 VO</b>；同域调用方是 {@code BlogContentPlanController}。
     *
     * <p>名字带 {@code ForView} 后缀，是因为端口方法 {@link #updateStatus(Long, Long, String)}
     * 已经占用了规范名 {@code updateStatus}：两者参数表完全相同、只差返回类型，
     * 而 Java 不允许只靠返回类型重载。按本仓库既有的做法 ——
     * <b>端口方法拿规范名，同域的富方法加后缀说明它服务谁</b>。</p>
     */
    @Transactional
    public BlogContentPlanVO updateStatusForView(Long userId, Long planId, String action) {
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

    /**
     * {@inheritDoc}
     *
     * <p>与 {@link #updateStatusForView} 同一实现体，只换返回类型。</p>
     */
    @Override
    @Transactional
    public ContentPlanView updateStatus(Long userId, Long planId, String action) {
        return toView(updateStatusForView(userId, planId, action));
    }

    /**
     * {@inheritDoc}
     *
     * <p>把契约修改草稿装回域请求体后转调
     * {@link #update(Long, Long, BlogContentPlanUpdateRequest)}，再把 VO 收窄。
     * 这是本类"同名不同参"的第二个实例：域请求体有 15 个字段，契约草稿只有 8 个。</p>
     */
    @Override
    @Transactional
    public ContentPlanView update(Long userId, Long planId, ContentPlanEdit edit) {
        return toView(update(userId, planId, toUpdateRequest(edit)));
    }

    /** {@inheritDoc} */
    @Override
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
     * 以及 billingPort.chargeTokens 写库（L142）。
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
                // ⚠️ 两个端口的 isAuthorized 签名逐字相同，接收者写反**编译得过**（不会报错、指标不动）。
                // 钉住这条的是 BlogContentPlanServiceTest 里那条不对称桩用例（一个授权、一个不授权）。
                // ⚠️ 查不出来时降级为 false，绝不把主流程带崩 —— 见类注释「两个派生字段的失败语义」。
                .csdnAuthorized(credentialAuthorizedOrFalse(
                        "csdn", userId, () -> csdnCredentialPort.isAuthorized(userId)))
                .juejinAuthorized(credentialAuthorizedOrFalse(
                        "juejin", userId, () -> juejinCredentialPort.isAuthorized(userId)))
                .build();
    }

    /**
     * 凭证查询的 <b>fail-soft</b> 包装：只回答"这个属主的凭证现在可用吗"，
     * 查不出来就<b>按不可用处理</b>。
     *
     * <p>⚠️ try 块里<b>只有一条语句</b>，而那条语句就是外部查询本身 —— 这是刻意的：
     * 容错的范围必须与"外部查询失败"这个语义严格等宽。把这个 try 扩大到包住方法体，
     * 或把两个平台的查询合进同一个 try，都会让主流程的失败被静默吞掉；后者的症状尤其隐蔽
     * —— CSDN 查失败会让掘金那个字段一起变成 {@code false}，而"两个都是 false"看起来完全正常。</p>
     *
     * <p>⚠️ 捕 {@link RuntimeException} 而不是某个具体类型：端口契约说的是
     * "未授权返回 {@code false}、不抛"，但<b>查询机制本身</b>失败（库不可用、拆服务后
     * 提供方不可达）抛什么类型取决于实现，而契约不许依赖框架类型（ADR-1）。
     * 范围由"单语句 try"保证，<b>不由异常类型保证</b>。</p>
     *
     * <p>⚠️ 只打消息、<b>不打堆栈</b>：{@code list} / {@code listAll} 是逐行调 {@code toVO}，
     * 一个坏掉的凭证查询会产生 2N 条堆栈，把日志淹掉。要看堆栈时按这个标记去查。</p>
     */
    private boolean credentialAuthorizedOrFalse(String platform, Long userId, BooleanSupplier lookup) {
        try {
            return lookup.getAsBoolean();
        } catch (RuntimeException e) {
            log.warn("[BlogPlan] {} platform={} userId={}: {}",
                    VO_CREDENTIAL_ENRICHMENT_FAILURE, platform, userId, rootMessage(e));
            return false;
        }
    }

    /** 取根因消息，给被吞掉的异常留一句能看的日志。 */
    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    /**
     * 富 VO → 契约读模型。这是<b>唯一</b>一处往 {@link ContentPlanView} 落字段的地方。
     *
     * <p>为什么不写一个"实体 → 读模型"的映射（那样读路径可以少构造一个 VO，也少两次
     * 凭据查询）：那会变成两份字段映射，而两份迟早不一致 —— 而且不一致的症状是
     * "列表接口和创建接口对同一条计划返回不同的值"，很难发现。
     * 这里选择让读路径也经过 {@link #toVO}，换来"只有一处映射"这个性质。</p>
     *
     * <p>逐个字段手写，<b>不要</b> {@code BeanUtils.copyProperties} / 反射：字段名对不上时
     * 反射版本静默留 {@code null}，手写版本编译期报错。</p>
     *
     * <p>⚠️ {@code triggerTimes} 取的是 VO 上的值，而 VO 用的是
     * {@link BlogContentPlan#allTriggerTimes()}（"JSON 列 → 时间列表"的解析入口，带兜底）。
     * 换回 {@code getTriggerTimesJson()} 会让工具看到的触发时间与前端看到的不一致。</p>
     */
    private ContentPlanView toView(BlogContentPlanVO vo) {
        if (vo == null) return null;
        return new ContentPlanView(
                vo.getId(),
                vo.getTopic(),
                vo.getStatus(),
                vo.getTimezone(),
                vo.getTriggerTimes(),
                vo.getSyncCsdn(),
                vo.getSyncJuejin(),
                vo.getRunOnce(),
                vo.getNextRunAt(),
                vo.getLastRunAt(),
                vo.getErrorMessage());
    }

    /**
     * 契约草稿 → 域创建请求体。
     *
     * <p>刻意<b>不</b>在这里补默认值：{@code create} 的实现体已经做了
     * （{@code syncCsdn} 的 {@code null → FALSE}、{@code autoPublish} 的默认真、
     * 主题与触发时间的规范化）。在这里再补一次会变成第二份规则，两份迟早不一致。</p>
     */
    private BlogContentPlanCreateRequest toCreateRequest(ContentPlanDraft draft) {
        BlogContentPlanCreateRequest request = new BlogContentPlanCreateRequest();
        request.setClientRequestId(draft.clientRequestId());
        request.setTopic(draft.topic());
        request.setTopics(draft.topics());
        request.setTriggerTimes(draft.triggerTimes());
        request.setTriggerTime(draft.triggerTime());
        request.setTimezone(draft.timezone());
        request.setAudience(draft.audience());
        request.setTone(draft.tone());
        request.setSyncCsdn(draft.syncCsdn());
        request.setSyncJuejin(draft.syncJuejin());
        return request;
    }

    /**
     * 契约修改草稿 → 域修改请求体。
     *
     * <p>同上：不补默认值。{@code update} 的实现体按"字段非空才改"的语义处理，
     * 所以 {@code null} 在这里就是"保持原值"的意思，不能被换算层改写。</p>
     */
    private BlogContentPlanUpdateRequest toUpdateRequest(ContentPlanEdit edit) {
        BlogContentPlanUpdateRequest request = new BlogContentPlanUpdateRequest();
        request.setTopic(edit.topic());
        request.setTriggerTimes(edit.triggerTimes());
        request.setTriggerTime(edit.triggerTime());
        request.setTimezone(edit.timezone());
        request.setAudience(edit.audience());
        request.setTone(edit.tone());
        request.setSyncCsdn(edit.syncCsdn());
        request.setSyncJuejin(edit.syncJuejin());
        return request;
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
