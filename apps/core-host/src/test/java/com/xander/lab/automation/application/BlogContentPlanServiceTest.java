package com.xander.lab.automation.application;

import com.xander.lab.automation.api.BlogContentPlanAiGenerateRequest;
import com.xander.lab.automation.api.BlogContentPlanCreateRequest;
import com.xander.lab.automation.api.BlogContentPlanTopicsGenerateRequest;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
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

    /**
     * generateTopics 必须【没有】事务注解（V9 §75 跨域事务边界）。
     *
     * <p>它调用的 {@code agentService.generateTopicSeries} 会做外部模型 HTTP 调用
     * 并调用 {@code billingPort.chargeTokens} 写库。加了 {@code @Transactional}
     * （尤其是 {@code readOnly = true}）会把这两件事圈进一个只读事务：</p>
     *
     * <ul>
     *   <li>readOnly 在 MySQL 上只是提示、不是约束 → <b>写进去不报错</b>，零症状；</li>
     *   <li>模型调用慢时，一个只读事务会持有数据库连接数秒到数十秒。</li>
     * </ul>
     *
     * <p>只能靠注解反射守：纯单测拿不到 Spring 代理，而把它加回去之后
     * 所有功能测试仍然全绿 —— 症状只在生产高峰（连接池耗尽）出现。</p>
     */
    @Test
    void generateTopicsMustNotBeTransactional() throws NoSuchMethodException {
        Method method = BlogContentPlanService.class.getMethod(
                "generateTopics", Long.class, BlogContentPlanTopicsGenerateRequest.class);

        assertThat(method.getAnnotation(Transactional.class))
                .as("generateTopics 不得带 @Transactional —— 它经 agentService 做外部模型调用"
                        + "与计费写库，圈进事务（尤其 readOnly）会让慢模型调用长时间占用数据库连接，"
                        + "而 readOnly 下的写不会报错、没有任何功能性症状")
                .isNull();
    }

    /** nextRunAfter 是纯计算 + 单条查询，保持只读事务是合理的 —— 这条与上一条一起
     * 钉住"哪些方法该有、哪些不该有"，避免下次修订一刀切地全删或全加。 */
    @Test
    void nextRunAfterStaysReadOnlyTransactional() throws NoSuchMethodException {
        Method method = BlogContentPlanService.class.getMethod(
                "nextRunAfter", Long.class, Long.class, long.class);

        Transactional transactional = method.getAnnotation(Transactional.class);
        assertThat(transactional).as("nextRunAfter 应保留只读事务").isNotNull();
        assertThat(transactional.readOnly()).as("nextRunAfter 不应变成读写事务").isTrue();
    }
}
