package com.xander.lab.automation.infrastructure;

import com.xander.lab.automation.application.BlogPlanRunDispatcher;
import com.xander.lab.automation.domain.BlogContentPlan;
import com.xander.lab.automation.domain.BlogPlanRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** Polls due content plans every few seconds, recovers stale claims, and hands
 * each claimed plan to the run dispatcher for a bounded background worker.
 * Multiple instances stay correct because claiming is an atomic UPDATE. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "blog-content-plan.enabled", havingValue = "true")
public class BlogContentPlanScheduler {

    private static final String STALE_CLAIM_ERROR =
            "执行进程意外中断，本次触发已跳过并顺延到下一周期";

    private final com.xander.lab.automation.infrastructure.BlogContentPlanMapper planMapper;
    private final com.xander.lab.automation.infrastructure.BlogPlanRunMapper runMapper;
    private final BlogPlanRunDispatcher dispatcher;

    @Value("${blog-content-plan.batch-size:5}")
    private int batchSize;

    @Value("${blog-content-plan.claim-timeout-ms:1800000}")
    private long claimTimeoutMs;

    @Scheduled(
            fixedDelayString = "${blog-content-plan.poll-interval-ms:15000}",
            initialDelayString = "${blog-content-plan.initial-delay-ms:15000}"
    )
    public void dispatchDuePlans() {
        try {
            recoverStaleClaims();

            List<BlogContentPlan> due = planMapper.selectDuePlans(Math.max(1, batchSize));
            for (BlogContentPlan plan : due) {
                boolean enqueued = dispatcher.dispatchDue(plan);
                if (enqueued) {
                    log.info("[BlogPlan] enqueued run for plan={} scheduled at {}", plan.getId(), plan.getNextRunAt());
                }
            }
        } catch (Exception e) {
            log.error("[BlogPlan] poller failed", e);
        }
    }

    /** Fails plans claimed by a crashed instance and fails their in-flight runs
     * so history shows a terminal state instead of a forever-GENERATING row. */
    private void recoverStaleClaims() {
        List<BlogContentPlan> stale = planMapper.selectStaleClaims(Math.max(1, claimTimeoutMs), Math.max(1, batchSize));
        for (BlogContentPlan plan : stale) {
            var next = com.xander.lab.automation.domain.EmailReminderScheduleCalculator.resolveNextAfterSend(
                    com.xander.lab.automation.domain.EmailReminderScheduleCalculator.FREQ_DAILY,
                    com.xander.lab.automation.domain.EmailReminderScheduleCalculator.resolveZone(plan.getTimezone()),
                    plan.getTriggerTime(),
                    null,
                    null,
                    plan.getNextRunAt() == null ? java.time.Instant.now() : plan.getNextRunAt(),
                    java.time.Instant.now()
            );
            int recovered = planMapper.recoverStaleClaim(plan.getId(), next, STALE_CLAIM_ERROR);
            if (recovered == 1) {
                runMapper.failInFlightRunsForPlan(plan.getId(), STALE_CLAIM_ERROR);
                log.warn("[BlogPlan] recovered stale plan={}", plan.getId());
            }
        }
    }
}