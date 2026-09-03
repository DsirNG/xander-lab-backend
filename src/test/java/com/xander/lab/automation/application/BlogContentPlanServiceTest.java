package com.xander.lab.automation.application;

import com.xander.lab.automation.api.BlogContentPlanAiGenerateRequest;
import com.xander.lab.automation.api.BlogContentPlanCreateRequest;
import com.xander.lab.automation.api.BlogContentPlanUpdateRequest;
import com.xander.lab.automation.api.BlogContentPlanVO;
import com.xander.lab.automation.domain.BlogContentPlan;
import com.xander.lab.automation.infrastructure.BlogContentPlanMapper;
import com.xander.lab.automation.infrastructure.BlogPlanRunMapper;
import com.xander.lab.blogagent.application.BlogAgentService;
import com.xander.lab.integration.mcp.csdn.application.CsdnCredentialService;
import com.xander.lab.integration.mcp.juejin.application.JuejinCredentialService;
import com.xander.lab.recitation.application.RecitationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** AI 生成计划: one topic per day, one-shot plans starting tomorrow, and quota
 * checks that cover the whole batch up front. */
@ExtendWith(MockitoExtension.class)
class BlogContentPlanServiceTest {

    @Mock private BlogContentPlanMapper planMapper;
    @Mock private BlogPlanRunMapper runMapper;
    @Mock private BlogPlanRunService runService;
    @Mock private CsdnCredentialService csdnCredentialService;
    @Mock private JuejinCredentialService juejinCredentialService;
    @Mock private BlogAgentService agentService;
    @Mock private RecitationService recitationService;

    private BlogContentPlanService newService() {
        BlogContentPlanService service = new BlogContentPlanService(planMapper, runMapper, runService,
                csdnCredentialService, juejinCredentialService, agentService, recitationService);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "maxActivePerUser", 50L);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "maxCreatedPerDay", 20L);
        return service;
    }

    @Test
    void aiGenerateCreatesOneShotPlansStartingTomorrowAtSameLocalTime() {
        when(planMapper.lockUserForPlanCreation(7L)).thenReturn(7L);
        when(planMapper.selectList(any())).thenReturn(List.of());
        when(agentService.generateTopicSeries(eq(7L), eq("Kubernetes"), any(), any(),
                eq(3), any())).thenReturn(List.of("K8s 调度器", "K8s 网络", "K8s 存储"));
        when(planMapper.countActivePlans(7L)).thenReturn(2L);
        when(planMapper.countCreatedInPastDay(7L)).thenReturn(0L);
        when(planMapper.selectById(any())).thenAnswer(invocation -> {
            BlogContentPlan plan = new BlogContentPlan();
            plan.setId(invocation.getArgument(0));
            plan.setUserId(7L);
            plan.setTopic("K8s 调度器");
            plan.setTimezone("Asia/Shanghai");
            plan.setTriggerTime("09:00");
            plan.setRunOnce(true);
            plan.setStatus(BlogContentPlan.STATUS_ACTIVE);
            return plan;
        });

        BlogContentPlanAiGenerateRequest request = new BlogContentPlanAiGenerateRequest();
        request.setTopic("Kubernetes");
        request.setDays(3);
        request.setTime("09:00");

        BlogContentPlanService service = newService();
        List<BlogContentPlanVO> created = service.aiGenerate(7L, request);

        assertThat(created).hasSize(3);
        org.mockito.Mockito.verify(planMapper, org.mockito.Mockito.times(3))
                .insert(org.mockito.ArgumentMatchers.argThat(plan -> {
                    assertThat(plan.getRunOnce()).isTrue();
                    assertThat(plan.getTriggerTime()).isEqualTo("09:00");
                    assertThat(plan.getTimezone()).isEqualTo("Asia/Shanghai");
                    return true;
                }));
    }

    @Test
    void createReturnsExistingPlanForTheSameIdempotencyKeyWithoutInsertingAgain() {
        when(planMapper.lockUserForPlanCreation(7L)).thenReturn(7L);
        BlogContentPlan existing = new BlogContentPlan();
        existing.setId(18L);
        existing.setUserId(7L);
        existing.setClientRequestId("invocation-1");
        existing.setTopic("Existing plan");
        existing.setTimezone("Asia/Shanghai");
        existing.setTriggerTime("09:00");
        existing.setStatus(BlogContentPlan.STATUS_ACTIVE);
        when(planMapper.selectOne(any())).thenReturn(existing);

        BlogContentPlanCreateRequest request = new BlogContentPlanCreateRequest();
        request.setClientRequestId("invocation-1");

        BlogContentPlanVO result = newService().create(7L, request);

        assertThat(result.getId()).isEqualTo(18L);
        verify(planMapper, never()).insert(any(BlogContentPlan.class));
    }

    @Test
    void aiGeneratePlansFireAtSameDailyTimeStartingTomorrow() {
        when(planMapper.lockUserForPlanCreation(7L)).thenReturn(7L);
        when(planMapper.selectList(any())).thenReturn(List.of());
        when(agentService.generateTopicSeries(eq(7L), eq("Kubernetes"), any(), any(),
                eq(3), any())).thenReturn(List.of("A", "B", "C"));
        when(planMapper.countActivePlans(7L)).thenReturn(2L);
        when(planMapper.countCreatedInPastDay(7L)).thenReturn(0L);
        when(planMapper.selectById(any())).thenAnswer(invocation -> {
            BlogContentPlan plan = new BlogContentPlan();
            plan.setId(invocation.getArgument(0));
            plan.setUserId(7L);
            plan.setTimezone("Asia/Shanghai");
            plan.setTriggerTime("09:00");
            plan.setRunOnce(true);
            plan.setStatus(BlogContentPlan.STATUS_ACTIVE);
            return plan;
        });

        BlogContentPlanAiGenerateRequest request = new BlogContentPlanAiGenerateRequest();
        request.setTopic("Kubernetes");
        request.setDays(3);
        request.setTime("09:00");

        BlogContentPlanService service = newService();
        service.aiGenerate(7L, request);

        java.util.List<BlogContentPlan> inserted = new java.util.ArrayList<>();
        org.mockito.ArgumentCaptor<BlogContentPlan> captor =
                org.mockito.ArgumentCaptor.forClass(BlogContentPlan.class);
        org.mockito.Mockito.verify(planMapper, org.mockito.Mockito.times(3)).insert(captor.capture());
        inserted.addAll(captor.getAllValues());

        Instant first = inserted.get(0).getNextRunAt();
        Instant second = inserted.get(1).getNextRunAt();
        Instant third = inserted.get(2).getNextRunAt();
        // 每天同一时刻（UTC 相差 24 小时整），第一天是"明天"
        assertThat(second).isEqualTo(first.plusSeconds(86400));
        assertThat(third).isEqualTo(first.plusSeconds(172800));
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        assertThat(first.atZone(zone).getHour()).isEqualTo(9);
        assertThat(first.atZone(zone).getMinute()).isZero();
        // 不在今天，而是从明天开始
        assertThat(first.atZone(zone).toLocalDate())
                .isAfter(java.time.LocalDate.now(zone));
    }

    @Test
    void aiGenerateRejectsBatchThatExceedsActivePlanQuota() {
        when(planMapper.lockUserForPlanCreation(7L)).thenReturn(7L);
        when(planMapper.selectList(any())).thenReturn(List.of());
        when(agentService.generateTopicSeries(eq(7L), eq("Kubernetes"), any(), any(),
                eq(3), any())).thenReturn(List.of("A", "B", "C"));
        when(planMapper.countActivePlans(7L)).thenReturn(48L);

        BlogContentPlanAiGenerateRequest request = new BlogContentPlanAiGenerateRequest();
        request.setTopic("Kubernetes");
        request.setDays(3);

        BlogContentPlanService service = newService();
        assertThatThrownBy(() -> service.aiGenerate(7L, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    void aiGenerateRejectsBatchThatExceedsDailyCreationQuota() {
        when(planMapper.lockUserForPlanCreation(7L)).thenReturn(7L);
        when(planMapper.selectList(any())).thenReturn(List.of());
        when(agentService.generateTopicSeries(eq(7L), eq("Kubernetes"), any(), any(),
                eq(3), any())).thenReturn(List.of("A", "B", "C"));
        when(planMapper.countActivePlans(7L)).thenReturn(2L);
        when(planMapper.countCreatedInPastDay(7L)).thenReturn(19L);

        BlogContentPlanAiGenerateRequest request = new BlogContentPlanAiGenerateRequest();
        request.setTopic("Kubernetes");
        request.setDays(3);

        BlogContentPlanService service = newService();
        assertThatThrownBy(() -> service.aiGenerate(7L, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    void aiGenerateFailsWhenAgentReturnsNoTopics() {
        when(planMapper.selectList(any())).thenReturn(List.of());
        when(agentService.generateTopicSeries(eq(7L), eq("Kubernetes"), any(), any(),
                eq(7), any())).thenReturn(List.of());

        BlogContentPlanAiGenerateRequest request = new BlogContentPlanAiGenerateRequest();
        request.setTopic("Kubernetes");

        BlogContentPlanService service = newService();
        assertThatThrownBy(() -> service.aiGenerate(7L, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }

    /** FAILED is a terminal one-shot state: like FINISHED, the plan must not be
     * editable anymore. */
    @Test
    void updateRejectsFailedPlanAsTerminal() {
        BlogContentPlan failed = new BlogContentPlan();
        failed.setId(9L);
        failed.setUserId(7L);
        failed.setTopic("旧主题");
        failed.setStatus(BlogContentPlan.STATUS_FAILED);
        when(planMapper.selectOne(any())).thenReturn(failed);

        BlogContentPlanService service = newService();
        assertThatThrownBy(() -> service.update(7L, 9L, new BlogContentPlanUpdateRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(HttpStatus.CONFLICT.value());
    }
}
