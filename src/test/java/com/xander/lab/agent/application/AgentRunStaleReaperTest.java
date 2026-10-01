package com.xander.lab.agent.application;

import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentRun;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import com.xander.lab.agent.infrastructure.AgentConversationProperties;
import com.xander.lab.agent.infrastructure.AgentRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskExecutor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 兜底调度器：扫谁、交给谁，以及**自动恢复的三条安全阀**。
 *
 * <p>"什么算超时"的判据归 {@link AgentRunLifecycle#recoverStale} 所有，"怎么重入"归
 * {@link AgentRunLifecycle#resume} 所有，本类都不重复实现。所以这里钉的是它自己那份判断：
 * 扫描窗口、逐条委派、单条失败不中断整批，以及三条安全阀各自真的把自动恢复挡下来了。</p>
 *
 * <p>安全阀之所以值得逐条钉，是因为它们的失败方式是**静默放大故障**：漏掉"最多一次"，
 * 一个依赖坏工具的 Run 会被每个扫描周期重跑一遍；漏掉"用户取消过"，用户的停止请求会被
 * 调度器悄悄撤回。两者都不会报错，只会一直转。</p>
 */
@ExtendWith(MockitoExtension.class)
class AgentRunStaleReaperTest {

    private static final long CONVERSATION_ID = 7L;
    private static final long USER_ID = 42L;
    private static final long RUN_ID = 9L;
    private static final long RUN_VERSION = 3L;
    private static final String INTENT_EPOCH = "6f1c0a44-1f0e-4a3c-9a2f-6b7c1d2e3f40";

    @Mock
    private AgentRunMapper runMapper;
    @Mock
    private AgentConversationMapper conversationMapper;
    @Mock
    private AgentRunLifecycle runLifecycle;
    @Mock
    private AgentExecutionJournal executionJournal;
    @Mock
    private ObjectProvider<AgentConversationService> conversationServiceProvider;
    @Mock
    private AgentConversationService conversationService;
    @Mock
    private TaskExecutor agentTaskExecutor;

    private AgentRunStaleReaper reaper;

    @BeforeEach
    void setUp() {
        AgentConversationProperties properties = new AgentConversationProperties();
        properties.setStaleConversationMinutes(30);
        reaper = new AgentRunStaleReaper(runMapper, conversationMapper, runLifecycle, properties,
                conversationServiceProvider, agentTaskExecutor, executionJournal);
    }

    /** 扫描窗口必须来自配置的 stale 分钟数：写死一个常量会让"会话多快算中断"有两处真相。 */
    @Test
    void scansWithTheConfiguredStalenessWindow() {
        when(runMapper.selectStaleRunningRuns(any(), anyInt())).thenReturn(List.of());

        reaper.reapStaleRuns();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(runMapper).selectStaleRunningRuns(cutoff.capture(), anyInt());
        assertThat(cutoff.getValue()).isAfter(LocalDateTime.now().minusMinutes(31));
        assertThat(cutoff.getValue()).isBeforeOrEqualTo(LocalDateTime.now().minusMinutes(30));
    }

    /** 收敛动作全部委托出去：调度器自己一行状态都不写，否则超时判据会有两份实现。 */
    @Test
    void delegatesSettlementToTheLifecycleInsteadOfWritingStateItself() {
        AgentConversation stale = conversation();
        when(runMapper.selectStaleRunningRuns(any(), anyInt())).thenReturn(List.of(run()));
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(stale);

        reaper.reapStaleRuns();

        verify(runLifecycle).recoverStale(eq(stale), eq(USER_ID));
        verify(conversationMapper, never()).update(any(), any());
    }

    /** 一条候选炸掉不能拖垮整批：后面那些同样卡在 running 里的会话还得被收掉。 */
    @Test
    void oneFailingCandidateDoesNotAbortTheRestOfTheBatch() {
        when(runMapper.selectStaleRunningRuns(any(), anyInt()))
                .thenReturn(List.of(run(1L, 7L), run(2L, 8L), run(3L, 9L)));
        when(conversationMapper.selectById(7L)).thenReturn(conversation(7L, 42L));
        when(conversationMapper.selectById(8L)).thenThrow(new IllegalStateException("库连接抖了一下"));
        when(conversationMapper.selectById(9L)).thenReturn(conversation(9L, 44L));

        reaper.reapStaleRuns();

        verify(runLifecycle).recoverStale(any(), eq(42L));
        verify(runLifecycle).recoverStale(any(), eq(44L));
    }

    /** 候选是"可能超时"，会话可能已经被删掉；这种情况静默跳过，不是错误。 */
    @Test
    void aCandidateWhoseConversationDisappearedIsSkipped() {
        when(runMapper.selectStaleRunningRuns(any(), anyInt())).thenReturn(List.of(run()));
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(null);

        reaper.reapStaleRuns();

        verify(runLifecycle, never()).recoverStale(any(), any());
    }

    // ---- 自动恢复 ----

    /**
     * 崩溃的 Run 要被自动接回去，而不是等用户回来点"继续"。
     *
     * <p>这条是 {@code intent_epoch} 能成立的前提：用户手打"继续"会开新意图，上一轮已经发出、
     * 只是没确认的副作用就再也认不出来了。自动恢复复用同一个 Run 与同一个周期，重试才被识别为重试。</p>
     */
    @Test
    void anInterruptedRunIsAutomaticallyResumedWithinItsOwnIntentEpoch() {
        givenReaped(run());

        reaper.reapStaleRuns();

        verify(runLifecycle).resume(CONVERSATION_ID, USER_ID, RUN_ID);
        ArgumentCaptor<Runnable> submitted = ArgumentCaptor.forClass(Runnable.class);
        verify(agentTaskExecutor).execute(submitted.capture());
        submitted.getValue().run();
        // 恢复的是一轮已经在跑的执行，所以没有"当前用户消息"（null）；监听器必须是空的：
        // 这条恢复没有客户端在等，事件由落库侧负责，用户下次打开会话时重放。
        verify(conversationService).executeClaimedTurn(
                eq(CONVERSATION_ID), eq(USER_ID), eq(RUN_VERSION), isNull(), eq(false), any());
    }

    /** 用户按过停止的 Run 绝不能被自动恢复——那是用户的意志，调度器不能替他撤回。 */
    @Test
    void aUserCancelledRunIsNeverAutomaticallyResumed() {
        AgentConversation cancelled = conversation();
        cancelled.setCancelRequested(1);
        when(runMapper.selectStaleRunningRuns(any(), anyInt())).thenReturn(List.of(run()));
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(cancelled);
        when(runLifecycle.recoverStale(cancelled, USER_ID)).thenReturn(true);

        reaper.reapStaleRuns();

        verify(executionJournal, never()).markAutoResumed(anyLong(), any());
        verify(runLifecycle, never()).resume(anyLong(), any(), anyLong());
        verify(agentTaskExecutor, never()).execute(any());
    }

    /**
     * 已经自动恢复过一次的 Run 只标记、不再提交。
     *
     * <p>没有这条，一个依赖坏工具的 Run 会被每个扫描周期捞起来重跑一次，永远转下去——
     * 而这恰好是最需要被限制的那类故障。</p>
     */
    @Test
    void aRunThatAlreadyUsedItsAutomaticResumeIsOnlyMarked() {
        AgentRun alreadyResumed = run();
        alreadyResumed.setAutoResumedAt(LocalDateTime.now().minusMinutes(5));
        givenReaped(alreadyResumed);

        reaper.reapStaleRuns();

        verify(executionJournal, never()).markAutoResumed(anyLong(), any());
        verify(runLifecycle, never()).resume(anyLong(), any(), anyLong());
        verify(agentTaskExecutor, never()).execute(any());
    }

    /**
     * 迁移之前创建的 Run 没有周期标识，凭猜测补一个会把"这次重试属于哪次诉求"写错。
     *
     * <p>而那个归属正是幂等采纳的判据，所以这一类只标记、不自动恢复。</p>
     */
    @Test
    void aRunFromBeforeIntentEpochsIsNotAutomaticallyResumed() {
        AgentRun legacy = run();
        legacy.setIntentEpoch(null);
        givenReaped(legacy);

        reaper.reapStaleRuns();

        verify(executionJournal, never()).markAutoResumed(anyLong(), any());
        verify(runLifecycle, never()).resume(anyLong(), any(), anyLong());
    }

    /** 没赢下收敛（说明别人已经收过）就不该继续走自动恢复。 */
    @Test
    void aRunWhoseSettlementWasNotWonIsNotAutomaticallyResumed() {
        when(runMapper.selectStaleRunningRuns(any(), anyInt())).thenReturn(List.of(run()));
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation());
        when(runLifecycle.recoverStale(any(), eq(USER_ID))).thenReturn(false);

        reaper.reapStaleRuns();

        verify(executionJournal, never()).markAutoResumed(anyLong(), any());
    }

    /**
     * 名额必须**在提交之前**记账。
     *
     * <p>顺序反了的话，"提交成功但记账失败"或"进程恰好死在两者之间"会让这个 Run 每个周期都被
     * 再恢复一次，而"最多一次"正是唯一屏障。记账在前最多浪费一次恢复机会（安全方向），
     * 在后则可能永远转下去。</p>
     */
    @Test
    void theAutomaticResumeQuotaIsClaimedBeforeTheRunIsSubmitted() {
        givenReaped(run());

        reaper.reapStaleRuns();

        InOrder order = inOrder(executionJournal, runLifecycle, agentTaskExecutor);
        order.verify(executionJournal).markAutoResumed(RUN_ID, USER_ID);
        order.verify(runLifecycle).resume(CONVERSATION_ID, USER_ID, RUN_ID);
        order.verify(agentTaskExecutor).execute(any());
    }

    /** 抢不到名额说明另一个实例已经恢复了它；重复提交会让同一个 Run 被两个 worker 同时跑。 */
    @Test
    void aRunWhoseQuotaWasAlreadyTakenByAnotherInstanceIsNotSubmitted() {
        givenReaped(run());
        when(executionJournal.markAutoResumed(RUN_ID, USER_ID)).thenReturn(false);

        reaper.reapStaleRuns();

        verify(runLifecycle, never()).resume(anyLong(), any(), anyLong());
        verify(agentTaskExecutor, never()).execute(any());
    }

    /**
     * 恢复被拒绝是一个业务结果，不是一个要往上抛的错误。
     *
     * <p>典型来源：两次读之间状态又被谁改过。此时 Run 已经是 {@code INTERRUPTED}，
     * 用户还能自己继续，所以记一条日志就够了。</p>
     */
    @Test
    void aRefusedResumeIsRecordedAndNotSubmitted() {
        givenReaped(run());
        when(runLifecycle.resume(CONVERSATION_ID, USER_ID, RUN_ID))
                .thenThrow(new AgentRunNotResumableException(CONVERSATION_ID, RUN_VERSION, "该执行已被新一轮顶替"));

        assertThatCode(() -> reaper.reapStaleRuns()).doesNotThrowAnyException();

        verify(agentTaskExecutor, never()).execute(any());
    }

    /**
     * 工作线程池拒收不能把整批拖垮，也不能把 Run 写成失败。
     *
     * <p>此时 Run 已经是 {@code RUNNING}、名额也已经花掉，落一条日志即可——写成失败反而会掩盖
     * "其实还能继续"这个事实。</p>
     */
    @Test
    void aRejectedWorkerSubmissionDoesNotEscapeTheBatch() {
        givenReaped(run());
        org.mockito.Mockito.doThrow(new java.util.concurrent.RejectedExecutionException("队列满"))
                .when(agentTaskExecutor).execute(any());

        assertThatCode(() -> reaper.reapStaleRuns()).doesNotThrowAnyException();
    }

    /** 没有接上工作线程池时（聚焦测试/降级部署）退化成"只收敛、不恢复"，而不是抛异常。 */
    @Test
    void withoutAWorkerPoolTheRunIsSettledButNotResumed() {
        AgentConversationProperties properties = new AgentConversationProperties();
        properties.setStaleConversationMinutes(30);
        AgentRunStaleReaper markOnly =
                new AgentRunStaleReaper(runMapper, conversationMapper, runLifecycle, properties);
        when(runMapper.selectStaleRunningRuns(any(), anyInt())).thenReturn(List.of(run()));
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation());
        when(runLifecycle.recoverStale(any(), eq(USER_ID))).thenReturn(true);

        markOnly.reapStaleRuns();

        verify(runLifecycle).recoverStale(any(), eq(USER_ID));
        verify(runLifecycle, never()).resume(anyLong(), any(), anyLong());
    }

    // ---- 辅助 ----

    /** 一条"已被成功收敛、可以自动恢复"的候选：恢复成功 + 有周期 + 没用过名额 + 工作池可用。 */
    private void givenReaped(AgentRun run) {
        lenient().when(runMapper.selectStaleRunningRuns(any(), anyInt())).thenReturn(List.of(run));
        lenient().when(conversationMapper.selectById(run.getConversationId())).thenReturn(conversation());
        lenient().when(runLifecycle.recoverStale(any(), eq(USER_ID))).thenReturn(true);
        lenient().when(executionJournal.markAutoResumed(run.getId(), USER_ID)).thenReturn(true);
        lenient().when(conversationServiceProvider.getObject()).thenReturn(conversationService);
        lenient().when(runLifecycle.resume(anyLong(), any(), anyLong())).thenReturn(RUN_VERSION);
    }

    private AgentRun run() {
        return run(RUN_ID, CONVERSATION_ID);
    }

    private AgentRun run(Long id, Long conversationId) {
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setConversationId(conversationId);
        run.setUserId(USER_ID);
        run.setRunVersion(RUN_VERSION);
        run.setStatus(AgentRun.STATUS_RUNNING);
        run.setIntentEpoch(INTENT_EPOCH);
        return run;
    }

    private AgentConversation conversation() {
        return conversation(CONVERSATION_ID, USER_ID);
    }

    private AgentConversation conversation(Long id, Long userId) {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(id);
        conversation.setUserId(userId);
        conversation.setStatus("running");
        conversation.setRunVersion(RUN_VERSION);
        conversation.setCancelRequested(0);
        return conversation;
    }
}
