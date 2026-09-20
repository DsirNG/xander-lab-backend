package com.xander.lab.automation.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.xander.lab.automation.api.BlogContentPlanAiGenerateRequest;
import com.xander.lab.automation.api.BlogContentPlanCreateRequest;
import com.xander.lab.automation.api.BlogContentPlanTopicsGenerateRequest;
import com.xander.lab.automation.api.BlogContentPlanUpdateRequest;
import com.xander.lab.automation.api.BlogContentPlanVO;
import com.xander.lab.automation.domain.BlogContentPlan;
import com.xander.lab.automation.infrastructure.BlogContentPlanMapper;
import com.xander.lab.automation.infrastructure.BlogPlanRunMapper;
import com.xander.lab.automation.spi.ContentPlanView;
import com.xander.lab.blogagent.application.BlogAgentService;
import com.xander.lab.integration.api.CsdnCredentialPort;
import com.xander.lab.integration.api.JuejinCredentialPort;
import com.xander.lab.recitation.spi.RecitationMaterialPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
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
import static org.mockito.Mockito.lenient;
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
    @Mock private CsdnCredentialPort csdnCredentialPort;
    @Mock private JuejinCredentialPort juejinCredentialPort;
    @Mock private BlogAgentService agentService;
    @Mock private RecitationMaterialPort materialPort;

    /** 日志断言用：派生字段查询失败的标记必须能被日志系统看见（本仓今天没有指标）。 */
    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(BlogContentPlanService.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachLogAppender() {
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void detachLogAppender() {
        serviceLogger.detachAppender(appender);
        appender.stop();
    }

    private BlogContentPlanService newService() {
        BlogContentPlanService service = new BlogContentPlanService(planMapper, runMapper, runService,
                csdnCredentialPort, juejinCredentialPort, agentService, materialPort);
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
     * generateTopics 必须【没有】事务注解。
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

    /**
     * ⚠️ {@link CsdnCredentialPort} 与 {@link JuejinCredentialPort} 的
     * {@code isAuthorized(Long)} <b>方法名与签名逐字相同</b>，所以把 {@code toVO} 里
     * 那两个接收者写反<b>编译得过</b>：不会报错、耦合指标一动不动、门禁全绿。
     *
     * <p>钉住它的唯一办法是让两个桩取<b>不同</b>的值。两个都返回 {@code true}
     * （或都 {@code false}）的用例，把接收者对调之后<b>照样全绿</b> ——
     * 这正是"判据强度不够是测不出来的"那一族：能证明已写的断言会红，
     * 证明不了"断言选的值分不开两个被测对象"。</p>
     *
     * <p>方向与 {@code BlogPlanRunServiceTest} 里那条相反（那边 CSDN 授权、掘金不授权），
     * 两条合起来把两个方向的写反都堵住。</p>
     */
    @Test
    void theTwoCredentialPortsAreNotInterchangeableEvenThoughTheirSignaturesAreIdentical() {
        BlogContentPlan plan = new BlogContentPlan();
        plan.setId(41L);
        plan.setUserId(7L);
        plan.setStatus(BlogContentPlan.STATUS_ACTIVE);
        when(planMapper.selectOne(any())).thenReturn(plan);
        when(csdnCredentialPort.isAuthorized(7L)).thenReturn(false);
        when(juejinCredentialPort.isAuthorized(7L)).thenReturn(true);

        BlogContentPlanVO vo = newService().detail(7L, 41L);

        assertThat(vo.getCsdnAuthorized())
                .as("CSDN 那个派生字段必须来自 CsdnCredentialPort —— 取反了就说明接收者写反了")
                .isFalse();
        assertThat(vo.getJuejinAuthorized())
                .as("掘金那个派生字段必须来自 JuejinCredentialPort")
                .isTrue();
    }

    // ---------------------------------------------------------------------
    // 派生字段查询失败必须 fail-soft
    //
    // 语义角色：csdnAuthorized / juejinAuthorized 只是"补充信息"，不参与鉴权、
    // 租户隔离、执行身份判定，也不决定是否允许操作 ⇒ 它们的查询失败**不得**改变
    // 主流程的成功/失败语义。反面同样成立：容错的范围必须只包住那次查询本身，
    // 主流程的 401 / 404 / 409 一律照抛。
    //
    // ⚠️ 这一组用例与上面那条"不对称桩"用例是两件事，缺一不可：
    //   不对称桩守的是"接收者写反"，这一组守的是"失败被吞掉/吞得太宽"。
    // ---------------------------------------------------------------------

    private BlogContentPlan activePlan() {
        BlogContentPlan plan = new BlogContentPlan();
        plan.setId(41L);
        plan.setUserId(7L);
        plan.setTopic("K8s 调度器");
        plan.setStatus(BlogContentPlan.STATUS_ACTIVE);
        plan.setTimezone("Asia/Shanghai");
        plan.setTriggerTime("09:00");
        plan.setSyncCsdn(Boolean.TRUE);
        plan.setSyncJuejin(Boolean.TRUE);
        return plan;
    }

    /**
     * ① 端口路径：凭证查询失败时，一次状态变更必须<b>照常成功</b>。
     *
     * <p>这就是一个已登记的缺陷：{@code ContentPlanPort.updateStatus} 会经过
     * {@code toVO}，而 {@code toVO} 为了两个智能体并不读的派生字段去查凭证 ——
     * 一个补充字段的查询失败，能让一次状态变更整体失败。</p>
     *
     * <p>⚠️ 没有真实事务代理，所以"回滚"这一步在这里量不到。能量到的是它的<b>直接原因</b>：
     * 异常不再逃出这个 {@code @Transactional} 方法，而"有异常逃出去"正是回滚的触发条件。</p>
     */
    @Test
    void thePortPathSurvivesAFailingCredentialLookup() {
        when(planMapper.selectOne(any())).thenReturn(activePlan());
        when(planMapper.pauseOwnedPlan(41L, 7L)).thenReturn(1);
        when(csdnCredentialPort.isAuthorized(7L))
                .thenThrow(new QueryTimeoutException("csdn credential lookup timed out"));
        when(juejinCredentialPort.isAuthorized(7L))
                .thenThrow(new QueryTimeoutException("juejin credential lookup timed out"));

        ContentPlanView view = newService().updateStatus(7L, 41L, "PAUSED");

        assertThat(view).as("凭证查询失败不得让端口路径整体失败").isNotNull();
        assertThat(view.id()).isEqualTo(41L);
        assertThat(view.topic()).isEqualTo("K8s 调度器");
        assertThat(view.timezone()).isEqualTo("Asia/Shanghai");
        verify(planMapper).pauseOwnedPlan(41L, 7L);
    }

    /**
     * ② 前端路径：凭证查询失败时，VO 必须<b>照常返回全部字段</b>，只有那两个派生字段降级为
     * {@code false}。主流程的成功/失败语义不变。
     *
     * <p>这条同时挡住另一种过度容错：把 {@code toVO} 整个包起来、失败时返回
     * {@code null} 或一个空壳 VO —— 那会让"计划存在"静默变成"查不到"。</p>
     */
    @Test
    void theFrontendPathStillReturnsTheWholePlanWithTheDerivedFieldsDegraded() {
        when(planMapper.selectOne(any())).thenReturn(activePlan());
        when(csdnCredentialPort.isAuthorized(7L))
                .thenThrow(new DataAccessResourceFailureException("credential table unreachable"));
        when(juejinCredentialPort.isAuthorized(7L))
                .thenThrow(new DataAccessResourceFailureException("credential table unreachable"));

        BlogContentPlanVO vo = newService().detail(7L, 41L);

        assertThat(vo).as("凭证查询失败不得让详情返回 null").isNotNull();
        assertThat(vo.getId()).isEqualTo(41L);
        assertThat(vo.getTopic()).isEqualTo("K8s 调度器");
        assertThat(vo.getTimezone()).isEqualTo("Asia/Shanghai");
        assertThat(vo.getSyncCsdn()).isTrue();
        assertThat(vo.getCsdnAuthorized())
                .as("查不出来就按不可用处理，而不是把异常往外扔")
                .isFalse();
        assertThat(vo.getJuejinAuthorized()).isFalse();
    }

    /**
     * ③ 可观测性：失败必须留下一条带<b>固定标记</b>的 WARN，且标记与将来的指标名逐字相同。
     *
     * <p>本仓没有 Micrometer / Actuator（指标属于后续的可观测性工作），所以这条不变式今天由日志承载。
     * 没有这条断言的话，"fail-soft"会退化成"fail-silent" —— 一个永远不报错的降级路径，
     * 和"绿着撒谎的判据"是同一族。</p>
     */
    @Test
    void aFailingCredentialLookupLeavesAWarningWithTheStableMarker() {
        when(planMapper.selectOne(any())).thenReturn(activePlan());
        when(csdnCredentialPort.isAuthorized(7L))
                .thenThrow(new QueryTimeoutException("csdn credential lookup timed out"));
        when(juejinCredentialPort.isAuthorized(7L)).thenReturn(true);

        newService().detail(7L, 41L);

        assertThat(appender.list)
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .singleElement()
                .asString()
                .as("只失败的平台才该留日志：掘金那次查询是成功的")
                .contains("blog_plan_vo_credential_enrichment_failure")
                .contains("platform=csdn")
                .contains("userId=7")
                .contains("csdn credential lookup timed out");
    }

    /**
     * ④ 两个平台的查询必须<b>各自独立</b>地 fail-soft。
     *
     * <p>把两次查询合进同一个 try 是最省事、也最像样的写法，而它的症状是
     * "CSDN 查失败 → 掘金那个字段一起变成 {@code false}"。两个都是 {@code false}
     * 看起来完全正常，所以只有一条让两边取<b>不同</b>值的用例能发现它。</p>
     */
    @Test
    void onlyThePlatformWhoseLookupFailedDegradesTheOtherStillAnswers() {
        when(planMapper.selectOne(any())).thenReturn(activePlan());
        when(csdnCredentialPort.isAuthorized(7L))
                .thenThrow(new QueryTimeoutException("csdn credential lookup timed out"));
        when(juejinCredentialPort.isAuthorized(7L)).thenReturn(true);

        BlogContentPlanVO vo = newService().detail(7L, 41L);

        assertThat(vo.getCsdnAuthorized())
                .as("CSDN 那个派生字段必须来自 CsdnCredentialPort —— "
                        + "接收者写反、或两次查询被合并进同一个 try，都会让它读到掘金那边的值")
                .isFalse();
        assertThat(vo.getJuejinAuthorized())
                .as("CSDN 那次查询失败不得把掘金那个字段一起降级 —— 两次查询各自 fail-soft")
                .isTrue();
    }

    /**
     * ⑤ 容错的范围<b>不覆盖主流程</b>：401 / 404 一律照抛。
     *
     * <p>⚠️ 这条用例的绿色<b>不依赖凭证端口被调用</b> —— 404 与 401 都发生在 {@code toVO}
     * 之前，凭证端口根本走不到（所以桩是 {@code lenient} 的，这一点本身就是它要说的事）。
     * 它钉的是<b>容错的范围</b>这个形状：把 try 扩大到包住方法体，它就会变红。
     * 这也是"判据只朝一个方向看"那一族的正例用法 —— 拿它去断言它本该拒绝的形状。</p>
     */
    @Test
    void theCredentialFailSoftDoesNotSwallowTheMainFlowFailures() {
        lenient().when(csdnCredentialPort.isAuthorized(any()))
                .thenThrow(new QueryTimeoutException("csdn credential lookup timed out"));
        lenient().when(juejinCredentialPort.isAuthorized(any()))
                .thenThrow(new QueryTimeoutException("juejin credential lookup timed out"));
        when(planMapper.selectOne(any())).thenReturn(null);

        BlogContentPlanService service = newService();

        assertThatThrownBy(() -> service.detail(7L, 41L))
                .as("计划不存在必须仍然抛 404 —— 把容错扩大到方法体会把它吞成一个静默的成功")
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThatThrownBy(() -> service.detail(null, 41L))
                .as("未登录必须仍然抛 401")
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }
}
