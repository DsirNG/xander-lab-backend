package com.xander.lab.automation.application;

import com.xander.lab.automation.domain.BlogContentPlan;
import com.xander.lab.automation.domain.BlogPlanRun;
import com.xander.lab.automation.infrastructure.BlogContentPlanMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/** Bridges the database poller (which claims due plans atomically) and the slow
 * worker that actually generates and publishes. Claiming must never block the
 * poller thread, so each claimed plan is handed to a bounded executor. */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlogPlanRunDispatcher {

    private final BlogContentPlanMapper planMapper;
    private final BlogPlanRunService runService;
    private final TaskExecutor blogPlanRunExecutor;

    /** Processes one due plan: claims it, creates the run row idempotently,
     * and enqueues the worker. Returns true when a run was enqueued. */
    public boolean dispatchDue(BlogContentPlan plan) {
        String token = UUID.randomUUID().toString();
        if (planMapper.claimPlan(plan.getId(), token) != 1) {
            return false;
        }

        Instant scheduledAt = plan.getNextRunAt() == null ? Instant.now() : plan.getNextRunAt();
        BlogPlanRun run;
        try {
            run = runService.createScheduledRun(plan, scheduledAt, token);
        } catch (RuntimeException e) {
            // One-shot plans never reschedule: end them in FAILED so they cannot
            // fire daily and the user sees the real terminal state.
            if (Boolean.TRUE.equals(plan.getRunOnce())) {
                String message = rootMessage(e);
                planMapper.failOneShotPlan(plan.getId(), token, null, message);
                runService.failInFlightRuns(plan.getId(), message);
            } else {
                planMapper.recoverStaleClaim(plan.getId(),
                        runService.resolveNextRun(plan, Instant.now()),
                        rootMessage(e));
            }
            log.warn("[BlogPlan] create run failed for plan={}: {}", plan.getId(), rootMessage(e));
            return false;
        }
        if (run == null) {
            // Duplicate run row already exists; hand the plan back to ACTIVE
            // without creating another run for this trigger.
            if (Boolean.TRUE.equals(plan.getRunOnce())) {
                // 同一次触发已存在执行记录（多为上次实例中断遗留），无法补跑，落失败终态。
                String message = "同一次触发已存在执行记录，已跳过";
                planMapper.failOneShotPlan(plan.getId(), token, null, message);
                runService.failInFlightRuns(plan.getId(), message);
            } else {
                planMapper.recoverStaleClaim(plan.getId(),
                        runService.resolveNextRun(plan, Instant.now()),
                        "同一次触发已存在执行记录，已跳过");
            }
            return false;
        }

        try {
            blogPlanRunExecutor.execute(() -> {
                BlogContentPlan snapshot = planMapper.selectById(plan.getId());
                if (snapshot == null) {
                    return;
                }
                runService.executeRun(run, snapshot);
                if (Boolean.TRUE.equals(snapshot.getRunOnce())) {
                    // 一次性计划按本次运行的真实结果落终态：成功 -> FINISHED，
                    // 失败/未过审 -> FAILED，绝不把失败标成成功。
                    BlogPlanRun terminal = runService.requireRun(run.getId());
                    if (BlogPlanRun.STATUS_SUCCEEDED.equals(terminal.getStatus())) {
                        planMapper.finishOneShotPlan(snapshot.getId(), token, run.getScheduledAt());
                    } else {
                        String message = terminalErrorMessage(terminal);
                        planMapper.failOneShotPlan(snapshot.getId(), token, run.getScheduledAt(), message);
                        runService.failInFlightRuns(snapshot.getId(), message);
                    }
                } else {
                    Instant next = runService.resolveNextRun(snapshot, Instant.now());
                    planMapper.markRunFinished(snapshot.getId(), token, next, run.getScheduledAt());
                }
            });
            return true;
        } catch (RuntimeException e) {
            log.warn("[BlogPlan] executor rejected plan={}: {}", plan.getId(), rootMessage(e));
            return false;
        }
    }

    /** Enqueues a manual test run without touching the plan's schedule. */
    public void dispatchManual(BlogPlanRun run, Long userId) {
        try {
            blogPlanRunExecutor.execute(() -> {
                BlogContentPlan snapshot = planMapper.selectById(run.getPlanId());
                if (snapshot == null || !snapshot.getUserId().equals(userId)) {
                    return;
                }
                runService.executeRun(run, snapshot);
            });
        } catch (RuntimeException e) {
            log.warn("[BlogPlan] executor rejected manual run for plan={}: {}", run.getPlanId(), rootMessage(e));
            throw e;
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? "计划执行失败" : message;
    }

    /** Error text persisted on a failed one-shot plan: the run's own failure
     * message, the compliance rejection reason, or a generic fallback. */
    private String terminalErrorMessage(BlogPlanRun terminal) {
        if (terminal.getErrorMessage() != null && !terminal.getErrorMessage().isBlank()) {
            return terminal.getErrorMessage();
        }
        if (BlogPlanRun.STATUS_REVIEW_REJECTED.equals(terminal.getStatus())
                && terminal.getReviewReason() != null && !terminal.getReviewReason().isBlank()) {
            return terminal.getReviewReason();
        }
        return "计划执行失败";
    }
}