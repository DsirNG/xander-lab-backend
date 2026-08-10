package com.xander.lab.automation.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xander.lab.automation.api.BlogContentPlanCreateRequest;
import com.xander.lab.automation.api.BlogContentPlanUpdateRequest;
import com.xander.lab.automation.api.BlogContentPlanVO;
import com.xander.lab.automation.api.BlogPlanRunVO;
import com.xander.lab.automation.api.BlogPlanRunsPage;
import com.xander.lab.automation.domain.BlogContentPlan;
import com.xander.lab.automation.domain.BlogPlanRun;
import com.xander.lab.automation.domain.EmailReminderScheduleCalculator;
import com.xander.lab.automation.infrastructure.BlogContentPlanMapper;
import com.xander.lab.automation.infrastructure.BlogPlanRunMapper;
import com.xander.lab.integration.mcp.csdn.application.CsdnCredentialService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** C-end use cases for daily content plans: CRUD, lifecycle transitions,
 * manual test trigger, run history, and next-fire preview. */
@Service
@RequiredArgsConstructor
public class BlogContentPlanService {

    private static final String UNAUTHORIZED_MESSAGE = "未登录或登录已过期";

    private final BlogContentPlanMapper planMapper;
    private final BlogPlanRunMapper runMapper;
    private final BlogPlanRunService runService;
    private final CsdnCredentialService csdnCredentialService;

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

        String topic = request.getTopic().strip();
        ZoneId zone = EmailReminderScheduleCalculator.resolveZone(request.getTimezone());
        LocalTime time = EmailReminderScheduleCalculator.parseSendTime(request.getTriggerTime());

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
        Instant nextRun = EmailReminderScheduleCalculator.resolveFirstFire(
                EmailReminderScheduleCalculator.FREQ_DAILY,
                zone,
                null,
                time.format(DateTimeFormatter.ofPattern("HH:mm")),
                null,
                null,
                now);

        BlogContentPlan plan = new BlogContentPlan();
        plan.setUserId(userId);
        plan.setTopic(topic);
        plan.setAudience(strip(request.getAudience()));
        plan.setTone(strip(request.getTone()));
        plan.setTimezone(zone.getId());
        plan.setTriggerTime(time.format(DateTimeFormatter.ofPattern("HH:mm")));
        plan.setSyncCsdn(request.getSyncCsdn() == null ? Boolean.FALSE : request.getSyncCsdn());
        plan.setNextRunAt(nextRun);
        plan.setStatus(BlogContentPlan.STATUS_ACTIVE);
        planMapper.insert(plan);
        return toVO(planMapper.selectById(plan.getId()), userId);
    }

    @Transactional(readOnly = true)
    public List<BlogContentPlanVO> list(Long userId) {
        requireUser(userId);
        return planMapper.selectList(ownedWrapper(userId).orderByDesc(BlogContentPlan::getCreatedAt))
                .stream().map(plan -> toVO(plan, userId)).toList();
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
                || BlogContentPlan.STATUS_CANCELLED.equals(plan.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "计划执行中或已取消，暂不能修改");
        }

        String topic = request.getTopic();
        String timezone = request.getTimezone();
        String triggerTime = request.getTriggerTime();
        if (topic != null && !topic.isBlank()) {
            plan.setTopic(topic.strip());
        }
        if (request.getSyncCsdn() != null) {
            plan.setSyncCsdn(request.getSyncCsdn());
        }
        if (request.getAudience() != null) {
            plan.setAudience(strip(request.getAudience()));
        }
        if (request.getTone() != null) {
            plan.setTone(strip(request.getTone()));
        }

        boolean scheduleChanged = false;
        if (triggerTime != null && !triggerTime.isBlank()
                && !triggerTime.equals(plan.getTriggerTime())) {
            LocalTime time = EmailReminderScheduleCalculator.parseSendTime(triggerTime);
            plan.setTriggerTime(time.format(DateTimeFormatter.ofPattern("HH:mm")));
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
            plan.setNextRunAt(EmailReminderScheduleCalculator.resolveFirstFire(
                    EmailReminderScheduleCalculator.FREQ_DAILY,
                    zone,
                    null,
                    plan.getTriggerTime(),
                    null,
                    null,
                    Instant.now()));
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
                if (plan.getNextRunAt() == null || !plan.getNextRunAt().isAfter(now)) {
                    ZoneId zone = EmailReminderScheduleCalculator.resolveZone(plan.getTimezone());
                    Instant next = EmailReminderScheduleCalculator.resolveFirstFire(
                            EmailReminderScheduleCalculator.FREQ_DAILY,
                            zone,
                            null,
                            plan.getTriggerTime(),
                            null,
                            null,
                            now);
                    updated = planMapper.resumeOwnedPlanAt(planId, userId, next);
                } else {
                    updated = planMapper.resumeOwnedPlan(planId, userId);
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

    @Transactional(readOnly = true)
    public Instant nextRunAfter(Long userId, Long planId, long count) {
        requireUser(userId);
        BlogContentPlan plan = requireOwnedPlan(planId, userId);
        ZoneId zone = EmailReminderScheduleCalculator.resolveZone(plan.getTimezone());
        Instant cursor = Instant.now();
        for (long i = 0; i < Math.max(1, count); i++) {
            cursor = EmailReminderScheduleCalculator.resolveNextAfterSend(
                    EmailReminderScheduleCalculator.FREQ_DAILY,
                    zone,
                    plan.getTriggerTime(),
                    null,
                    null,
                    cursor,
                    Instant.now());
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

    private BlogContentPlanVO toVO(BlogContentPlan plan, Long userId) {
        if (plan == null) return null;
        return BlogContentPlanVO.builder()
                .id(plan.getId())
                .topic(plan.getTopic())
                .audience(plan.getAudience())
                .tone(plan.getTone())
                .timezone(plan.getTimezone())
                .triggerTime(plan.getTriggerTime())
                .syncCsdn(plan.getSyncCsdn())
                .nextRunAt(plan.getNextRunAt())
                .lastRunAt(plan.getLastRunAt())
                .status(plan.getStatus())
                .errorMessage(plan.getErrorMessage())
                .createdAt(plan.getCreatedAt())
                .updatedAt(plan.getUpdatedAt())
                .csdnAuthorized(csdnCredentialService.isAuthorized(userId))
                .build();
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