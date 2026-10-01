package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import com.xander.lab.agent.infrastructure.AgentConversationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 权威状态 → 会话投影的**写入**契约。
 *
 * <p>这是唯一钉住"真正写进 {@code agent_conversation.status} 的到底是什么值"的地方。
 * 以前那些值散在 {@code claim} / {@code fail} / {@code recoverStale} 里各写各的，
 * 现在全部由 {@link com.xander.lab.agent.domain.AgentRunStatusProjection} 算出来；
 * 这个测试保证没有哪条迁移又自己拼了一个字符串，或者把某个终态投影错了。</p>
 */
@ExtendWith(MockitoExtension.class)
class AgentRunLifecycleStatusTest {

    private static final long CONVERSATION_ID = 7L;
    private static final long USER_ID = 42L;
    private static final long RUN_VERSION = 3L;

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
        lenient().when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation());
        lenient().when(conversationMapper.update(any(), any())).thenReturn(1);
    }

    @Test
    void completingRecordsCompletedAndProjectsReady() {
        assertThat(lifecycle.complete(CONVERSATION_ID, USER_ID, RUN_VERSION).conversation().getStatus())
                .isEqualTo("ready");
        verify(executionJournal).completeRun(CONVERSATION_ID, USER_ID, RUN_VERSION);
    }

    /** 取消以前被记成 COMPLETED，与"模型正常做完"分不开；权威列现在必须是 CANCELLED。 */
    @Test
    void cancellingRecordsCancelledAndStillProjectsReady() {
        assertThat(lifecycle.cancel(CONVERSATION_ID, USER_ID, RUN_VERSION).conversation().getStatus())
                .isEqualTo("ready");
        verify(executionJournal).cancelRun(CONVERSATION_ID, USER_ID, RUN_VERSION);
    }

    /** 预算耗尽不是"做完了"，投影成独立值前端才能提示"可以继续"。 */
    @Test
    void exhaustingTheBudgetProjectsTheDedicatedValue() {
        assertThat(lifecycle.exhaustBudget(CONVERSATION_ID, USER_ID, RUN_VERSION).conversation().getStatus())
                .isEqualTo("budget_exhausted");
        verify(executionJournal).exhaustBudgetRun(CONVERSATION_ID, USER_ID, RUN_VERSION);
    }

    @Test
    void failingRecordsTheRunFailureAndProjectsFailed() {
        lifecycle.fail(CONVERSATION_ID, USER_ID, RUN_VERSION, "工具炸了");

        verify(executionJournal).failRun(CONVERSATION_ID, USER_ID, RUN_VERSION, "工具炸了");
        assertThat(writtenConversationStatuses()).contains("failed");
    }

    /**
     * 进程消失必须落 INTERRUPTED，不能落 FAILED。
     *
     * <p>两者都写成 {@code failed} 时前端分不出"重试有用"和"重试也没用"——
     * 这正是 B3 修的三处语义错误之一。</p>
     */
    @Test
    void aStaleRunBecomesInterruptedRatherThanFailed() {
        AgentConversation stale = staleConversation();

        assertThat(lifecycle.recoverStale(stale, USER_ID)).isTrue();

        // 第五个参数必须是会话租约的 updated_at，不能是"现在"：进程死掉到被扫描出来之间
        // 可能隔着几十分钟，用 now 结算会把这段死掉的时间也算成活跃时长。
        verify(executionJournal).interruptRun(CONVERSATION_ID, USER_ID, RUN_VERSION,
                "上一轮执行中断，可继续对话", stale.getUpdatedAt());
        assertThat(writtenConversationStatuses()).contains("interrupted").doesNotContain("failed");
    }

    /** 用户按过停止、worker 却先一步消失：如实说"已停止"，不要伪装成执行中断。 */
    @Test
    void aUserCancelledStaleRunIsRecordedAsStoppedNotAsACrash() {
        AgentConversation stale = staleConversation();
        stale.setCancelRequested(1);

        lifecycle.recoverStale(stale, USER_ID);

        verify(executionJournal).interruptRun(CONVERSATION_ID, USER_ID, RUN_VERSION,
                "任务已按你的请求停止", stale.getUpdatedAt());
    }

    /** 已经收口过的会话再被 reap 一次必须什么都不做：条件更新命中 0 行。 */
    @Test
    void anAlreadySettledConversationIsNotRecoveredTwice() {
        when(conversationMapper.update(any(), any())).thenReturn(0);
        AgentConversation stale = staleConversation();

        assertThat(lifecycle.recoverStale(stale, USER_ID)).isFalse();

        verify(executionJournal, org.mockito.Mockito.never())
                .interruptRun(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    /** 拒绝名单里的两个投影值必须真的把 claim 挡下来，并给出可区分的错误。 */
    @Test
    void claimRefusesExactlyTheUnclaimableProjections() {
        when(conversationMapper.update(any(), any())).thenReturn(0);

        AgentConversation running = conversation();
        running.setStatus("running");
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(running);
        assertThatThrownBy(() -> lifecycle.claim(CONVERSATION_ID, USER_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("正在执行中");

        AgentConversation awaiting = conversation();
        awaiting.setStatus("awaiting_approval");
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(awaiting);
        assertThatThrownBy(() -> lifecycle.claim(CONVERSATION_ID, USER_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("不能继续对话");
    }

    /** 可恢复态与终态都必须能被 claim 开新意图——否则用户会被锁在会话外面。 */
    @Test
    void everyClaimableProjectionActuallyClaims() {
        for (String status : List.of("ready", "paused", "interrupted", "budget_exhausted", "failed")) {
            AgentConversation claimable = conversation();
            claimable.setStatus(status);
            when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(claimable);
            when(conversationMapper.update(any(), any())).thenReturn(1);

            assertThat(lifecycle.claim(CONVERSATION_ID, USER_ID))
                    .as("投影 %s 必须可以开新意图", status)
                    .isEqualTo(RUN_VERSION);
        }
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

    private AgentConversation staleConversation() {
        AgentConversation conversation = conversation();
        conversation.setUpdatedAt(LocalDateTime.now().minusMinutes(31));
        return conversation;
    }

    private AgentConversation conversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setUserId(USER_ID);
        conversation.setStatus("running");
        conversation.setRunVersion(RUN_VERSION);
        conversation.setCancelRequested(0);
        conversation.setUpdatedAt(LocalDateTime.now());
        return conversation;
    }
}
