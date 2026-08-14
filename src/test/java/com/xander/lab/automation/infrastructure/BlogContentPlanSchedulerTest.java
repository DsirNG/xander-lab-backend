package com.xander.lab.automation.infrastructure;

import com.xander.lab.automation.application.BlogPlanRunDispatcher;
import com.xander.lab.automation.domain.BlogContentPlan;
import com.xander.lab.automation.domain.BlogPlanRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Stale-claim recovery must tell success from failure: a one-shot plan whose
 * run already succeeded is finished as success, otherwise it ends in FAILED. */
@ExtendWith(MockitoExtension.class)
class BlogContentPlanSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-08-14T01:00:00Z");

    @Mock
    private BlogContentPlanMapper planMapper;

    @Mock
    private BlogPlanRunMapper runMapper;

    @Mock
    private BlogPlanRunDispatcher dispatcher;

    private BlogContentPlanScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new BlogContentPlanScheduler(planMapper, runMapper, dispatcher);
        ReflectionTestUtils.setField(scheduler, "batchSize", 5);
        ReflectionTestUtils.setField(scheduler, "claimTimeoutMs", 1_800_000L);
        when(planMapper.selectDuePlans(anyInt())).thenReturn(List.of());
    }

    @Test
    void staleOneShotWithSucceededRunFinishesInsteadOfFailing() {
        BlogContentPlan plan = oneShotPlan("tok-1");
        when(planMapper.selectStaleClaims(1_800_000L, 5)).thenReturn(List.of(plan));
        when(runMapper.hasSucceededRun(1L)).thenReturn(true);

        scheduler.dispatchDuePlans();

        verify(planMapper).finishOneShotPlan(eq(1L), eq("tok-1"), eq(NOW));
        verify(planMapper, never()).recoverStaleOneShot(anyLong(), anyString());
        verify(runMapper, never()).failInFlightRunsForPlan(anyLong(), anyString());
    }

    @Test
    void staleOneShotWithoutSucceededRunEndsInFailedAndFailsInFlightRuns() {
        BlogContentPlan plan = oneShotPlan("tok-2");
        when(planMapper.selectStaleClaims(1_800_000L, 5)).thenReturn(List.of(plan));
        when(runMapper.hasSucceededRun(1L)).thenReturn(false);
        when(planMapper.recoverStaleOneShot(eq(1L), anyString())).thenReturn(1);

        scheduler.dispatchDuePlans();

        verify(planMapper).recoverStaleOneShot(eq(1L),
                eq("执行进程意外中断，本次触发未完成，计划已标记失败"));
        verify(runMapper).failInFlightRunsForPlan(eq(1L),
                eq("执行进程意外中断，本次触发未完成，计划已标记失败"));
        verify(planMapper, never()).finishOneShotPlan(anyLong(), anyString(), any());
    }

    @Test
    void staleRecurringPlanIsRescheduledToNextDailyTrigger() {
        BlogContentPlan plan = new BlogContentPlan();
        plan.setId(2L);
        plan.setTimezone("Asia/Shanghai");
        plan.setTriggerTime("09:00");
        plan.setStatus(BlogContentPlan.STATUS_RUNNING);
        plan.setNextRunAt(NOW);
        plan.setProcessingToken("tok-3");
        when(planMapper.selectStaleClaims(1_800_000L, 5)).thenReturn(List.of(plan));
        when(planMapper.recoverStaleClaim(eq(2L), any(), anyString())).thenReturn(1);

        scheduler.dispatchDuePlans();

        // 09:00 Asia/Shanghai = 01:00 UTC；恢复后顺延到明天的同一时刻
        verify(planMapper).recoverStaleClaim(eq(2L),
                eq(NOW.plusSeconds(86400)), anyString());
        verify(runMapper).failInFlightRunsForPlan(eq(2L), anyString());
        verify(planMapper, never()).recoverStaleOneShot(anyLong(), anyString());
    }

    private BlogContentPlan oneShotPlan(String token) {
        BlogContentPlan plan = new BlogContentPlan();
        plan.setId(1L);
        plan.setTimezone("Asia/Shanghai");
        plan.setTriggerTime("09:00");
        plan.setRunOnce(true);
        plan.setStatus(BlogContentPlan.STATUS_RUNNING);
        plan.setNextRunAt(NOW);
        plan.setProcessingToken(token);
        return plan;
    }
}