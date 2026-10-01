package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentRun;
import com.xander.lab.agent.domain.AgentStep;
import com.xander.lab.agent.domain.AgentToolInvocation;
import com.xander.lab.agent.domain.RunBudget;
import com.xander.lab.agent.domain.RunUsage;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import com.xander.lab.agent.infrastructure.AgentRunMapper;
import com.xander.lab.agent.infrastructure.AgentStepMapper;
import com.xander.lab.agent.infrastructure.AgentToolInvocationMapper;
import com.xander.lab.billing.application.BillingService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预算快照与**活跃时长记账**。
 *
 * <p>这一批最容易写错、也最难在线上发现的不是预算判定本身，而是时长的**结算时刻**：
 * 记多了一段，长任务会莫名中断；记少了一段，时长闸门形同虚设。所以每个"离开 RUNNING"的出口
 * 都单独钉一条，尤其崩溃恢复那条——它用的是"最后已知存活时刻"而不是"现在"。</p>
 */
@ExtendWith(MockitoExtension.class)
class AgentExecutionJournalBudgetTest {

    private static final long CONVERSATION_ID = 7L;
    private static final long USER_ID = 42L;
    private static final long RUN_VERSION = 3L;

    @Mock
    private AgentConversationMapper conversationMapper;
    @Mock
    private AgentRunMapper runMapper;
    @Mock
    private AgentStepMapper stepMapper;
    @Mock
    private AgentToolInvocationMapper invocationMapper;
    @Mock
    private BillingService billingService;

    private AgentExecutionJournal journal;

    /**
     * MyBatis-Plus 必须先知道实体元信息才能把 {@code AgentRun::getX} 解析成列名，
     * 否则 {@code LambdaUpdateWrapper} 一构造就抛 "can not find lambda cache"。
     * 这里测的正是"写进去的值对不对"，所以这个初始化是前提而不是可选项。
     */
    @BeforeAll
    static void initializeMybatisMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), AgentConversation.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), AgentRun.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), AgentStep.class);
        // AgentToolInvocation 也要注册：状态迁移成功后 settleInFlightWork 会去收口在途调用，
        // 那个分支同样构造 LambdaUpdateWrapper，不注册就会在"迁移成功"这条路径上炸。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), AgentToolInvocation.class);
    }

    @BeforeEach
    void setUp() {
        journal = new AgentExecutionJournal(conversationMapper, runMapper, stepMapper,
                invocationMapper, billingService);
        lenient().when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(runningConversation());
    }

    /** 进入 RUNNING 就开始计活跃时长：漏写这一列，整段执行时间永远不会被结算。 */
    @Test
    void startingARunFreezesTheBudgetAndOpensTheActiveClock() {
        when(runMapper.selectOne(any())).thenReturn(null);
        when(runMapper.insert(any())).thenReturn(1);
        RunBudget budget = new RunBudget(20, 60, 0, 0, 5000, 0, Duration.ofMinutes(30));

        journal.startRun(CONVERSATION_ID, USER_ID, RUN_VERSION, budget);

        ArgumentCaptor<AgentRun> inserted = ArgumentCaptor.forClass(AgentRun.class);
        verify(runMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getBudgetJson()).contains("\"maxSteps\":20").contains("\"maxToolCalls\":60");
        assertThat(inserted.getValue().getLastRunningAt()).isNotNull();
        assertThat(inserted.getValue().getActiveDurationMs()).isZero();
    }

    @Test
    void theFrozenBudgetIsReadBackRatherThanTheCurrentConfiguration() {
        when(runMapper.selectOne(any())).thenReturn(runWith(run -> run.setBudgetJson(
                "{\"maxSteps\":3,\"maxToolCalls\":7,\"maxActiveSeconds\":120}")));

        RunBudget loaded = journal.loadBudget(CONVERSATION_ID, RUN_VERSION).orElseThrow();

        assertThat(loaded.maxSteps()).isEqualTo(3);
        assertThat(loaded.maxToolCalls()).isEqualTo(7);
        assertThat(loaded.maxActiveDuration()).isEqualTo(Duration.ofMinutes(2));
    }

    /** 快照缺失/损坏时返回空，让调用方退回配置——**不要**静默返回"无限制"。 */
    @Test
    void aMissingOrCorruptBudgetSnapshotYieldsEmptyInsteadOfUnlimited() {
        when(runMapper.selectOne(any())).thenReturn(runWith(run -> run.setBudgetJson(null)));
        assertThat(journal.loadBudget(CONVERSATION_ID, RUN_VERSION)).isEmpty();

        when(runMapper.selectOne(any())).thenReturn(runWith(run -> run.setBudgetJson("{不是 JSON")));
        assertThat(journal.loadBudget(CONVERSATION_ID, RUN_VERSION)).isEmpty();
    }

    /** 用量聚合：步数、调用条数、token 各自来自它的事实源，毫分按模型分别折算后求和。 */
    @Test
    void usageAggregationPullsEachDimensionFromItsOwnFactSource() {
        when(runMapper.selectOne(any())).thenReturn(runWith(run -> {
            run.setActiveDurationMs(0L);
            run.setLastRunningAt(LocalDateTime.now().minusSeconds(10));
        }));
        when(stepMapper.selectList(any())).thenReturn(List.of(
                completedStep(1, 100, 0, 50, "model-a"),
                completedStep(2, 200, 100, 60, "model-b"),
                runningStep(3)));
        when(invocationMapper.selectCount(any())).thenReturn(4L);
        when(billingService.estimateMilliPoints(any(), eq("model-a"))).thenReturn(11L);
        when(billingService.estimateMilliPoints(any(), eq("model-b"))).thenReturn(22L);

        RunUsage usage = journal.loadUsage(CONVERSATION_ID, RUN_VERSION);

        assertThat(usage.steps()).isEqualTo(3);
        assertThat(usage.toolCalls()).isEqualTo(4L);
        assertThat(usage.inputTokens()).isEqualTo(300L);
        assertThat(usage.cachedTokens()).isEqualTo(100L);
        assertThat(usage.outputTokens()).isEqualTo(110L);
        // 两个模型分别折算再求和：混着算会得到一个既不是 A 也不是 B 的数字。
        assertThat(usage.milliPoints()).isEqualTo(33L);
        // 正在进行的那一段也要算进去，否则"跑了 29 分钟"会被看成"才跑了 0 分钟"。
        assertThat(usage.activeDuration()).isGreaterThanOrEqualTo(Duration.ofSeconds(10));
    }

    /**
     * 崩溃恢复结算到"最后已知存活时刻"，**不是现在**。
     *
     * <p>进程死掉到被扫描出来之间可能隔着几十分钟。用 now 结算等于把这段死掉的时间也记成
     * 活跃时长——一次崩溃白送一截预算，而现象是"某些任务莫名被判超时"，极难复现。</p>
     */
    @Test
    void crashRecoverySettlesUpToTheLastKnownAliveInstantNotToNow() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lastAlive = now.minusSeconds(60);
        when(runMapper.selectOne(any())).thenReturn(runWith(run -> {
            run.setActiveDurationMs(0L);
            run.setLastRunningAt(now.minusSeconds(3600));
        }));
        when(runMapper.update(any(), any())).thenReturn(1);

        journal.interruptRun(CONVERSATION_ID, USER_ID, RUN_VERSION, "worker vanished", lastAlive);

        assertThat(writtenActiveDurationMs()).isEqualTo(3_540_000L);
    }

    /** 正常收口用"现在"结算，并把 last_running_at 清掉——非 NULL 表示"此刻正在 RUNNING"。 */
    @Test
    void aNormalTerminalTransitionSettlesToNowAndClearsTheRunningMarker() {
        when(runMapper.selectOne(any())).thenReturn(runWith(run -> {
            run.setActiveDurationMs(5_000L);
            run.setLastRunningAt(LocalDateTime.now().minusSeconds(30));
        }));
        when(runMapper.update(any(), any())).thenReturn(1);

        journal.failRun(CONVERSATION_ID, USER_ID, RUN_VERSION, "工具炸了");

        assertThat(writtenActiveDurationMs()).isBetween(35_000L, 60_000L);
        assertThat(writtenUpdateSql()).contains("last_running_at = NULL");
    }

    /** 等待审批不算活跃时间：用户可能出去吃饭三小时才批准。 */
    @Test
    void waitingForApprovalSettlesAndStopsTheClock() {
        when(runMapper.selectOne(any())).thenReturn(runWith(run -> {
            run.setActiveDurationMs(0L);
            run.setLastRunningAt(LocalDateTime.now().minusSeconds(20));
        }));
        when(runMapper.update(any(), any())).thenReturn(1);

        journal.waitRunForApproval(CONVERSATION_ID, USER_ID, RUN_VERSION);

        assertThat(writtenActiveDurationMs()).isBetween(20_000L, 40_000L);
        assertThat(writtenUpdateSql()).contains("last_running_at = NULL");
    }

    /**
     * 恢复（审批通过或调度器自动恢复）后必须重新开始计活跃时长。
     *
     * <p>漏掉这里，恢复之后的整段执行都不计入——时长闸门对"反复审批、每次跑很久"的任务
     * 完全失效，而这恰好是最需要被限制的那类任务。</p>
     *
     * <p>这条测试走 {@code resumeRun} 而不是某个审批专用方法：审批恢复与自动恢复是同一个动作，
     * 只是起始状态不同，所以"进入 RUNNING 要重启时钟"这件事只该有一个实现、一条断言。</p>
     */
    @Test
    void resumingRestartsTheActiveClock() {
        when(runMapper.update(any(), any())).thenReturn(1);

        assertThat(journal.resumeRun(17L, USER_ID, AgentRun.STATUS_WAITING_APPROVAL)).isTrue();

        assertThat(writtenRunParams()).anySatisfy(value ->
                assertThat(value).isInstanceOf(LocalDateTime.class));
    }

    /** 起始状态对不上（别人已经迁走了）必须返回 false，调用方据此拒绝这次恢复。 */
    @Test
    void aResumeWhoseStartingStateNoLongerMatchesLosesTheRace() {
        when(runMapper.update(any(), any())).thenReturn(0);

        assertThat(journal.resumeRun(17L, USER_ID, AgentRun.STATUS_INTERRUPTED)).isFalse();
    }

    /**
     * 恢复**不得**碰 {@code intent_epoch}：复用就是"不写"这个动作本身。
     *
     * <p>R-3 的断言放在这一层而不是 {@code AgentRunLifecycle}：周期字段只有这里能写，
     * 所以只有这里能坏。给 SET 子句补一个 {@code intent_epoch} 会让 resume 静默变成新周期——
     * 崩溃后的重试不再被识别为重试，同一个副作用被写第二遍，而 {@code resume()} 的测试
     * 全绿（它只看得见"调用了 resumeRun"）。</p>
     */
    @Test
    void resumingDoesNotMintANewIntentEpoch() {
        when(runMapper.update(any(), any())).thenReturn(1);

        assertThat(journal.resumeRun(17L, USER_ID, AgentRun.STATUS_INTERRUPTED)).isTrue();

        assertThat(writtenUpdateSql())
                .contains("last_running_at")
                .doesNotContain("intent_epoch")
                .doesNotContain("auto_resumed_at");
    }

    /**
     * 自动恢复名额是一次性 CAS：{@code auto_resumed_at IS NULL} 让并发的两个调度实例只有一个能抢到。
     *
     * <p>这条是"每个 Run 最多自动 resume 一次"的落点，而那条限制是防止一个必然失败的 Run
     * （依赖的工具坏了）被调度器无限重跑的唯一屏障。</p>
     */
    @Test
    void theAutomaticResumeQuotaIsAOneShotCas() {
        when(runMapper.update(any(), any())).thenReturn(1);
        assertThat(journal.markAutoResumed(17L, USER_ID)).isTrue();
        assertThat(writtenUpdateSql()).contains("auto_resumed_at");
        assertThat(writtenWhereSql()).contains("auto_resumed_at IS NULL");

        // 已经有值就抢不到：这一次调用什么都没改，调用方据此不提交。
        when(runMapper.update(any(), any())).thenReturn(0);
        assertThat(journal.markAutoResumed(17L, USER_ID)).isFalse();
    }

    /** 按主键读记录要容忍空 id（调用方可能拿着一个没落库的引用）而不是抛异常。 */
    @Test
    void findingARunByIdToleratesAMissingId() {
        assertThat(journal.findRun(null)).isEmpty();

        AgentRun stored = runWith(run -> { });
        when(runMapper.selectById(17L)).thenReturn(stored);
        assertThat(journal.findRun(17L)).contains(stored);
    }

    /** 已结算过的 Run 不该被二次结算：没有 last_running_at 就没有可加的时长。 */
    @Test
    void aRunWithoutARunningMarkerAccumulatesNothing() {
        when(runMapper.selectOne(any())).thenReturn(runWith(run -> {
            run.setActiveDurationMs(12_000L);
            run.setLastRunningAt(null);
        }));
        when(runMapper.update(any(), any())).thenReturn(1);

        journal.failRun(CONVERSATION_ID, USER_ID, RUN_VERSION, "炸了");

        assertThat(writtenActiveDurationMs()).isEqualTo(12_000L);
    }

    /** 状态没迁移成功时不该去收口在途记录：那说明这一轮已经被别人收过了。 */
    @Test
    void aLostTransitionRaceDoesNotSettleInFlightWork() {
        when(runMapper.selectOne(any())).thenReturn(runWith(run -> run.setLastRunningAt(null)));
        when(runMapper.update(any(), any())).thenReturn(0);

        journal.failRun(CONVERSATION_ID, USER_ID, RUN_VERSION, "炸了");

        verify(stepMapper, never()).update(any(), any());
    }

    /**
     * 从 SET 子句里精确取出 {@code active_duration_ms} 写进去的值。
     *
     * <p>不能用"参数表里最大的那个 Long"这种取巧写法：条件里的 {@code id}、{@code user_id}
     * 同样是 Long，取值取决于被测数据，测试会在某次数据变化后悄悄开始断言错对象。</p>
     */
    private long writtenActiveDurationMs() {
        String sqlSet = writtenUpdateSql();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile(
                                "active_duration_ms=#\\{ew\\.paramNameValuePairs\\.([A-Za-z0-9_]+)[^}]*}")
                        .matcher(sqlSet);
        if (!matcher.find()) return -1L;
        String paramName = matcher.group(1);
        return allRunUpdateWrappers().stream()
                .flatMap(wrapper -> wrapper.getParamNameValuePairs().entrySet().stream())
                .filter(entry -> paramName.equals(entry.getKey()))
                .map(entry -> (Long) entry.getValue())
                .findFirst()
                .orElse(-1L);
    }

    private List<Object> writtenRunParams() {
        return allRunUpdateWrappers().stream()
                .flatMap(wrapper -> wrapper.getParamNameValuePairs().values().stream())
                .toList();
    }

    private String writtenUpdateSql() {
        return allRunUpdateWrappers().stream()
                .map(LambdaUpdateWrapper::getSqlSet)
                .filter(java.util.Objects::nonNull)
                .reduce("", (left, right) -> left + " " + right);
    }

    /**
     * 条件更新里的 WHERE 片段。
     *
     * <p>用来钉"这次写入带的是什么守卫"——守卫比 SET 更容易写错，因为它写错时不会报错，
     * 只会多改或少改几行。</p>
     */
    private String writtenWhereSql() {
        return allRunUpdateWrappers().stream()
                .map(LambdaUpdateWrapper::getSqlSegment)
                .filter(java.util.Objects::nonNull)
                .reduce("", (left, right) -> left + " " + right);
    }

    @SuppressWarnings("unchecked")
    private List<LambdaUpdateWrapper<AgentRun>> allRunUpdateWrappers() {
        ArgumentCaptor<LambdaUpdateWrapper<AgentRun>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(runMapper, org.mockito.Mockito.atLeastOnce()).update(any(), captor.capture());
        return captor.getAllValues();
    }

    private AgentStep completedStep(int stepNo, long input, long cached, long output, String model) {
        AgentStep step = new AgentStep();
        step.setId((long) stepNo);
        step.setStepNo(stepNo);
        step.setStatus(AgentStep.STATUS_COMPLETED);
        step.setInputTokens(input);
        step.setCachedTokens(cached);
        step.setOutputTokens(output);
        step.setModelName(model);
        return step;
    }

    private AgentStep runningStep(int stepNo) {
        AgentStep step = new AgentStep();
        step.setId((long) stepNo);
        step.setStepNo(stepNo);
        step.setStatus(AgentStep.STATUS_RUNNING);
        step.setInputTokens(9_999L);
        step.setOutputTokens(9_999L);
        return step;
    }

    private AgentRun runWith(java.util.function.Consumer<AgentRun> customizer) {
        AgentRun run = new AgentRun();
        run.setId(17L);
        run.setUserId(USER_ID);
        run.setConversationId(CONVERSATION_ID);
        run.setRunVersion(RUN_VERSION);
        run.setStatus(AgentRun.STATUS_RUNNING);
        run.setActiveDurationMs(0L);
        customizer.accept(run);
        return run;
    }

    private AgentConversation runningConversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setUserId(USER_ID);
        conversation.setStatus("running");
        conversation.setRunVersion(RUN_VERSION);
        return conversation;
    }
}
