package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentRun;
import com.xander.lab.agent.domain.AgentRunStatusProjection;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import com.xander.lab.agent.infrastructure.AgentConversationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code resume()} 的三条规则（R-1 / R-2 / R-3）。
 *
 * <p>这是"崩溃后重试被识别为重试、而不是被当成一件新事"的唯一屏障：{@code resume()} 与
 * {@code claim()} 的区别全部体现在"代次与周期有没有被重新铸"，而那两件事都只发生在
 * {@code claim()} / {@code startRun()} 里。所以本类钉的核心不是"能不能恢复"，而是
 * **"恢复有没有偷偷换掉身份"**。</p>
 */
@ExtendWith(MockitoExtension.class)
class AgentRunResumeTest {

    private static final long CONVERSATION_ID = 7L;
    private static final long USER_ID = 42L;
    private static final long RUN_ID = 17L;
    private static final long RUN_VERSION = 3L;
    private static final String INTENT_EPOCH = "6f1c0a44-1f0e-4a3c-9a2f-6b7c1d2e3f40";

    @Mock
    private AgentConversationMapper conversationMapper;
    @Mock
    private AgentEventService eventService;
    @Mock
    private AgentExecutionJournal executionJournal;
    @Mock
    private ScheduledExecutorService agentLeaseRenewer;

    private AgentRunLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        AgentConversationProperties properties = new AgentConversationProperties();
        properties.setStaleConversationMinutes(30);
        TransactionTemplate template = mock(TransactionTemplate.class);
        lenient().doAnswer(invocation -> invocation.<TransactionCallback<?>>getArgument(0)
                        .doInTransaction(mock(TransactionStatus.class)))
                .when(template).execute(any());
        lifecycle = new AgentRunLifecycle(conversationMapper, eventService, executionJournal,
                properties, template, agentLeaseRenewer);
        lenient().when(conversationMapper.update(any(), any())).thenReturn(1);
        lenient().when(executionJournal.resumeRun(anyLong(), any(), anyString())).thenReturn(true);
    }

    // ---- R-1：同代次重入 ----

    /**
     * 恢复必须复用原来的 {@code run_version}，并且**绝不**走 {@code startRun}。
     *
     * <p>{@code startRun} 会铸新 {@code intent_epoch}、把 {@code nextStepNo} 归 1、把
     * {@code usageBeforeStep} 清零。任何一条都会让"崩溃后的重试"变成一个全新的意图，
     * 于是上一轮已经发出、只是没确认的副作用无法被识别为重试——这正是 D1 要证明不发生的事。</p>
     */
    @Test
    void resumingReusesTheRunVersionInsteadOfOpeningANewIntent() {
        givenRun(AgentRun.STATUS_INTERRUPTED, RUN_VERSION);
        givenConversation("interrupted", RUN_VERSION);

        assertThat(lifecycle.resume(CONVERSATION_ID, USER_ID, RUN_ID)).isEqualTo(RUN_VERSION);

        verify(executionJournal).resumeRun(RUN_ID, USER_ID, AgentRun.STATUS_INTERRUPTED);
        verify(executionJournal, never()).startRun(any(), any(), anyLong(), any());
    }

    /** 会话已经开了新一轮时"延续"会写进别人的代次，必须拒绝而不是硬写。 */
    @Test
    void resumingRefusesWhenTheConversationHasMovedOnToANewerGeneration() {
        givenRun(AgentRun.STATUS_INTERRUPTED, RUN_VERSION);
        givenConversation("interrupted", RUN_VERSION + 1);

        assertThatThrownBy(() -> lifecycle.resume(CONVERSATION_ID, USER_ID, RUN_ID))
                .isInstanceOf(AgentRunNotResumableException.class)
                .hasMessageContaining("顶替");

        verify(conversationMapper, never()).update(any(), any());
    }

    // ---- R-2：只从可恢复态进入 ----

    /** 四个可恢复态都必须能恢复，且投影值各不相同——这是"状态自读入以来没变过"的 CAS 前提。 */
    @Test
    void everyResumableStateCanBeResumed() {
        for (String status : AgentRun.resumableStatuses()) {
            givenRun(status, RUN_VERSION);
            givenConversation(AgentRunStatusProjection.of(status), RUN_VERSION);

            assertThat(lifecycle.resume(CONVERSATION_ID, USER_ID, RUN_ID))
                    .as("状态 %s 必须能恢复", status)
                    .isEqualTo(RUN_VERSION);
            assertThat(writtenConversationStatuses()).contains("running");
        }
    }

    /**
     * 终态一律不能恢复。
     *
     * <p>"终态不再迁移"是状态机的定义，不是策略：一个还能迁移的状态按定义就不是终态。
     * 放宽它等于给"一次 Run 多次尝试"的叙事留后门，而那件事该等 {@code agent_run_attempt}
     * 出现时再说。</p>
     */
    @Test
    void noTerminalStateCanBeResumed() {
        for (String status : List.of(AgentRun.STATUS_COMPLETED, AgentRun.STATUS_CANCELLED,
                AgentRun.STATUS_FAILED)) {
            givenRun(status, RUN_VERSION);
            givenConversation(AgentRunStatusProjection.of(status), RUN_VERSION);

            assertThatThrownBy(() -> lifecycle.resume(CONVERSATION_ID, USER_ID, RUN_ID))
                    .as("终态 %s 必须不可恢复", status)
                    .isInstanceOf(AgentRunNotResumableException.class)
                    .hasMessageContaining("不可恢复");
        }
    }

    /** 正在跑的 Run 也不可恢复：它没有被中断，没有需要延续的东西。 */
    @Test
    void anInProgressRunCannotBeResumed() {
        for (String status : List.of(AgentRun.STATUS_CREATED, AgentRun.STATUS_RUNNING)) {
            givenRun(status, RUN_VERSION);
            givenConversation("running", RUN_VERSION);

            assertThatThrownBy(() -> lifecycle.resume(CONVERSATION_ID, USER_ID, RUN_ID))
                    .isInstanceOf(AgentRunNotResumableException.class);
        }
    }

    // ---- 身份与归属 ----

    @Test
    void resumingRefusesAMissingRunRecord() {
        when(executionJournal.findRun(RUN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> lifecycle.resume(CONVERSATION_ID, USER_ID, RUN_ID))
                .isInstanceOf(AgentRunNotResumableException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void resumingRefusesARunOwnedByAnotherUser() {
        AgentRun run = run(AgentRun.STATUS_INTERRUPTED, RUN_VERSION);
        run.setUserId(USER_ID + 1);
        when(executionJournal.findRun(RUN_ID)).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> lifecycle.resume(CONVERSATION_ID, USER_ID, RUN_ID))
                .isInstanceOf(AgentRunNotResumableException.class)
                .hasMessageContaining("当前用户");
    }

    @Test
    void resumingRefusesARunBelongingToAnotherConversation() {
        AgentRun run = run(AgentRun.STATUS_INTERRUPTED, RUN_VERSION);
        run.setConversationId(CONVERSATION_ID + 1);
        when(executionJournal.findRun(RUN_ID)).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> lifecycle.resume(CONVERSATION_ID, USER_ID, RUN_ID))
                .isInstanceOf(AgentRunNotResumableException.class)
                .hasMessageContaining("不属于该会话");
    }

    // ---- 漂移与竞争 ----

    /**
     * 会话投影与 Run 状态不一致时必须响亮失败。
     *
     * <p>不一致说明有人绕过了 {@link AgentRunStatusProjection} 直接写会话状态。这时候"顺手修正"
     * 会把造成漂移的那处代码藏起来，让同一个 bug 下次换个地方再犯。</p>
     */
    @Test
    void resumingRefusesWhenTheConversationProjectionDisagreesWithTheRun() {
        givenRun(AgentRun.STATUS_INTERRUPTED, RUN_VERSION);
        givenConversation("running", RUN_VERSION);

        assertThatThrownBy(() -> lifecycle.resume(CONVERSATION_ID, USER_ID, RUN_ID))
                .isInstanceOf(AgentRunNotResumableException.class)
                .hasMessageContaining("不一致");

        verify(executionJournal, never()).resumeRun(anyLong(), any(), anyString());
    }

    /** 另一次恢复抢先迁走了投影：条件更新命中 0 行，这次恢复必须退出而不是叠加。 */
    @Test
    void resumingRefusesWhenAnotherResumeAlreadyMovedTheConversation() {
        givenRun(AgentRun.STATUS_INTERRUPTED, RUN_VERSION);
        givenConversation("interrupted", RUN_VERSION);
        when(conversationMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> lifecycle.resume(CONVERSATION_ID, USER_ID, RUN_ID))
                .isInstanceOf(AgentRunNotResumableException.class)
                .hasMessageContaining("抢先");

        verify(executionJournal, never()).resumeRun(anyLong(), any(), anyString());
    }

    /** 会话迁成功但运行侧 CAS 落空：整体必须回滚，不能让"会话说 running、运行说中断"落地。 */
    @Test
    void resumingRefusesWhenTheRunTransitionItselfLostTheRace() {
        givenRun(AgentRun.STATUS_INTERRUPTED, RUN_VERSION);
        givenConversation("interrupted", RUN_VERSION);
        when(executionJournal.resumeRun(anyLong(), any(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> lifecycle.resume(CONVERSATION_ID, USER_ID, RUN_ID))
                .isInstanceOf(AgentRunNotResumableException.class)
                .hasMessageContaining("抢先");
    }

    // ---- 投影语义 ----

    /**
     * 恢复会清掉陈旧的停止请求。
     *
     * <p>留着它，恢复后的第一个检查点就会自我取消——"恢复成功但立刻又死了"比不恢复更难排查。
     * 代价是 {@code resume()} 分辨不出"用户点了继续"和"调度器在重试"，所以
     * "用户取消过就不自动恢复"这条安全阀只能由调用方在**调用之前**判，
     * 见 {@code AgentRunStaleReaper}。</p>
     */
    @Test
    void resumingClearsAStaleStopRequestSoTheResumedWorkerDoesNotCancelItself() {
        givenRun(AgentRun.STATUS_INTERRUPTED, RUN_VERSION);
        givenConversation("interrupted", RUN_VERSION);

        lifecycle.resume(CONVERSATION_ID, USER_ID, RUN_ID);

        assertThat(writtenConversationStatuses()).contains("running", 0);
    }

    /** 恢复也要清掉上一轮的错误文案：它描述的状态已经过去了。 */
    @Test
    void resumingClearsTheStaleErrorMessage() {
        givenRun(AgentRun.STATUS_INTERRUPTED, RUN_VERSION);
        givenConversation("interrupted", RUN_VERSION);

        lifecycle.resume(CONVERSATION_ID, USER_ID, RUN_ID);

        assertThat(writtenConversationStatuses()).containsNull();
    }

    /**
     * 拒绝必须是 HTTP 409 而不是 500。
     *
     * <p>这是"资源当前状态不允许这个动作"，属于业务拒绝。不继承
     * {@code ResponseStatusException} 的话它会被全局兜底处理器收成 500，
     * 把一次正常的拒绝说成服务器故障。</p>
     */
    @Test
    void aRefusalIsAConflictRatherThanAServerError() {
        givenRun(AgentRun.STATUS_FAILED, RUN_VERSION);
        givenConversation("failed", RUN_VERSION);

        assertThatThrownBy(() -> lifecycle.resume(CONVERSATION_ID, USER_ID, RUN_ID))
                .isInstanceOf(AgentRunNotResumableException.class)
                .satisfies(exception -> assertThat(
                        ((org.springframework.web.server.ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    // ---- 审批是 resume 的特化，不是平行实现 ----

    /**
     * 审批恢复必须走同一个重入实现，只是额外落一条 {@code approval_approved}。
     *
     * <p>平行维护两套"怎么重入"的代价不是多写代码，而是两套的守卫迟早分叉——
     * 比如一边加了代次校验、另一边没有，于是审批路径成了绕过 R-1 的后门。</p>
     */
    @Test
    void resumingAfterApprovalIsTheSameReentryPlusItsOwnEvent() {
        givenRun(AgentRun.STATUS_WAITING_APPROVAL, RUN_VERSION);
        givenConversation("awaiting_approval", RUN_VERSION);

        lifecycle.resumeAfterApproval(CONVERSATION_ID, USER_ID, RUN_VERSION, RUN_ID, "payload");

        verify(executionJournal).resumeRun(RUN_ID, USER_ID, AgentRun.STATUS_WAITING_APPROVAL);
        verify(eventService).storeActive(CONVERSATION_ID, USER_ID, RUN_VERSION,
                "approval_approved", "payload");
    }

    /** 审批路径同样受 R-2 约束：状态不是待审批时不能借它绕过恢复规则。 */
    @Test
    void resumingAfterApprovalStillHonoursTheResumableRule() {
        givenRun(AgentRun.STATUS_FAILED, RUN_VERSION);
        givenConversation("failed", RUN_VERSION);

        assertThatThrownBy(() -> lifecycle.resumeAfterApproval(
                CONVERSATION_ID, USER_ID, RUN_VERSION, RUN_ID, "payload"))
                .isInstanceOf(AgentRunNotResumableException.class);

        verify(eventService, never()).storeActive(any(), any(), anyLong(), anyString(), any());
    }

    // ---- 辅助 ----

    private AgentRun run(String status, long runVersion) {
        AgentRun run = new AgentRun();
        run.setId(RUN_ID);
        run.setUserId(USER_ID);
        run.setConversationId(CONVERSATION_ID);
        run.setRunVersion(runVersion);
        run.setStatus(status);
        run.setIntentEpoch(INTENT_EPOCH);
        return run;
    }

    private void givenRun(String status, long runVersion) {
        lenient().when(executionJournal.findRun(RUN_ID)).thenReturn(Optional.of(run(status, runVersion)));
    }

    private void givenConversation(String status, long runVersion) {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setUserId(USER_ID);
        conversation.setStatus(status);
        conversation.setRunVersion(runVersion);
        conversation.setCancelRequested(1);
        conversation.setErrorMessage("上一轮执行中断，可继续对话");
        lenient().when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation);
    }

    /** 条件更新里实际写进去的会话状态值（MyBatis-Plus 把 SET 的实参放在参数表里）。 */
    private Collection<Object> writtenConversationStatuses() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<AgentConversation>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(conversationMapper, atLeastOnce()).update(any(), captor.capture());
        return captor.getAllValues().stream()
                .flatMap(wrapper -> wrapper.getParamNameValuePairs().values().stream())
                .toList();
    }
}
