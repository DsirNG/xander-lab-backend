package com.xander.lab.automation.application;

import com.xander.lab.automation.domain.BlogContentPlan;
import com.xander.lab.automation.domain.BlogPlanRun;
import com.xander.lab.automation.infrastructure.BlogContentPlanMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.SyncTaskExecutor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Claiming, idempotent run creation, and enqueueing across multi-instance
 * pollers must never create duplicate work for the same trigger instant. */
@ExtendWith(MockitoExtension.class)
class BlogPlanRunDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-07-24T09:00:00Z");

    @Mock
    private BlogContentPlanMapper planMapper;

    @Mock
    private BlogPlanRunService runService;

    private BlogPlanRunDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        TaskExecutor syncExecutor = new SyncTaskExecutor();
        dispatcher = new BlogPlanRunDispatcher(planMapper, runService, syncExecutor);
    }

    @Test
    void claimFailureSkipsEnqueue() {
        when(planMapper.claimPlan(eq(1L), any(String.class))).thenReturn(0);

        BlogContentPlan plan = plan(1L);
        boolean enqueued = dispatcher.dispatchDue(plan);

        assertThat(enqueued).isFalse();
        verify(runService, never()).createScheduledRun(any(), any(), any());
    }

    @Test
    void duplicateRunForSameScheduledAtIsNotEnqueuedAgain() {
        when(planMapper.claimPlan(eq(1L), any(String.class))).thenReturn(1);
        when(runService.createScheduledRun(any(), eq(NOW), any(String.class))).thenReturn(null);
        when(runService.resolveNextRun(any(), any())).thenReturn(NOW.plusSeconds(86400));

        BlogContentPlan plan = plan(1L);
        plan.setNextRunAt(NOW);
        boolean enqueued = dispatcher.dispatchDue(plan);

        assertThat(enqueued).isFalse();
        verify(planMapper).recoverStaleClaim(eq(1L), any(), any());
    }

    @Test
    void enqueuesWorkerAndReschedulesAfterCompletion() {
        when(planMapper.claimPlan(eq(1L), any(String.class))).thenReturn(1);
        BlogPlanRun run = run(11L, 1L, NOW);
        when(runService.createScheduledRun(any(), eq(NOW), any(String.class))).thenReturn(run);
        BlogContentPlan snapshot = plan(1L);
        when(planMapper.selectById(1L)).thenReturn(snapshot);
        when(runService.resolveNextRun(any(), any())).thenReturn(NOW.plusSeconds(86400));
        when(planMapper.markRunFinished(eq(1L), any(String.class), any(), eq(NOW))).thenReturn(1);

        BlogContentPlan plan = plan(1L);
        plan.setNextRunAt(NOW);
        boolean enqueued = dispatcher.dispatchDue(plan);

        assertThat(enqueued).isTrue();
        verify(runService).executeRun(run, snapshot);
        verify(planMapper).markRunFinished(eq(1L), any(String.class),
                eq(NOW.plusSeconds(86400)), eq(NOW));
    }

    @Test
    void createRunFailureRecoversClaimSoPollerCanRetryNextCycle() {
        when(planMapper.claimPlan(eq(1L), any(String.class))).thenReturn(1);
        when(runService.createScheduledRun(any(), eq(NOW), any(String.class)))
                .thenThrow(new IllegalStateException("dup key"));
        when(runService.resolveNextRun(any(), any())).thenReturn(NOW.plusSeconds(86400));

        BlogContentPlan plan = plan(1L);
        plan.setNextRunAt(NOW);
        boolean enqueued = dispatcher.dispatchDue(plan);

        assertThat(enqueued).isFalse();
        verify(planMapper).recoverStaleClaim(eq(1L), eq(NOW.plusSeconds(86400)), any(String.class));
    }

    /** One-shot plans finish (FINISHED, no next run) after their single run
     * instead of rescheduling the next daily trigger. */
    @Test
    void oneShotPlanFinishesInsteadOfRescheduling() {
        when(planMapper.claimPlan(eq(1L), any(String.class))).thenReturn(1);
        BlogPlanRun run = run(11L, 1L, NOW);
        when(runService.createScheduledRun(any(), eq(NOW), any(String.class))).thenReturn(run);
        BlogContentPlan snapshot = plan(1L);
        snapshot.setRunOnce(true);
        when(planMapper.selectById(1L)).thenReturn(snapshot);
        when(planMapper.finishOneShotPlan(eq(1L), any(String.class), eq(NOW))).thenReturn(1);

        BlogContentPlan plan = plan(1L);
        plan.setRunOnce(true);
        plan.setNextRunAt(NOW);
        boolean enqueued = dispatcher.dispatchDue(plan);

        assertThat(enqueued).isTrue();
        verify(runService).executeRun(run, snapshot);
        verify(planMapper).finishOneShotPlan(eq(1L), any(String.class), eq(NOW));
        verify(planMapper, never()).markRunFinished(any(), any(), any(), any());
    }

    /** When the run row already exists for a one-shot plan's fixed trigger,
     * the plan ends in FINISHED rather than being re-enqueued or rescheduled. */
    @Test
    void oneShotDuplicateRunFinishesPlan() {
        when(planMapper.claimPlan(eq(1L), any(String.class))).thenReturn(1);
        when(runService.createScheduledRun(any(), eq(NOW), any(String.class))).thenReturn(null);

        BlogContentPlan plan = plan(1L);
        plan.setRunOnce(true);
        plan.setNextRunAt(NOW);
        boolean enqueued = dispatcher.dispatchDue(plan);

        assertThat(enqueued).isFalse();
        verify(planMapper).finishOneShotPlan(eq(1L), any(String.class), isNull());
        verify(planMapper, never()).recoverStaleClaim(any(), any(), any());
    }

    private BlogContentPlan plan(Long id) {
        BlogContentPlan plan = new BlogContentPlan();
        plan.setId(id);
        plan.setUserId(7L);
        plan.setTopic("Kubernetes 入门");
        plan.setTimezone("Asia/Shanghai");
        plan.setTriggerTime("09:00");
        plan.setStatus(BlogContentPlan.STATUS_ACTIVE);
        plan.setNextRunAt(NOW);
        return plan;
    }

    private BlogPlanRun run(Long id, Long planId, Instant scheduledAt) {
        BlogPlanRun run = new BlogPlanRun();
        run.setId(id);
        run.setPlanId(planId);
        run.setScheduledAt(scheduledAt);
        run.setStatus(BlogPlanRun.STATUS_GENERATING);
        return run;
    }
}