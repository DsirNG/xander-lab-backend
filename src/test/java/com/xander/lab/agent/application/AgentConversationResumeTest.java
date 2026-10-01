package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentRun;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import com.xander.lab.agent.infrastructure.AgentConversationProperties;
import com.xander.lab.agent.infrastructure.AgentMessageMapper;
import com.xander.lab.agent.infrastructure.AgentModelClient;
import com.xander.lab.billing.application.BillingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code resume()} 的入口解析：会话 → 当前执行记录。
 *
 * <p>reaper 的自动恢复手里已经有候选 Run，所以它能直接传 run id；而"用户点继续"这条入口
 * 在 HTTP 路径上**只有会话 id**，必须先解析出当前 Run。这一层的风险全部集中在"解析"上：
 * 解析到一个已被顶替的旧执行、解析不到却不报错、或者顺手开了新一代次——
 * 三种都会让"继续"变成另一件事，而 {@code AgentRunResumeTest} 覆盖的是解析之后的部分，
 * 看不到这里。</p>
 *
 * <p>另外钉住一条分工：**本方法只改状态，不派活**。派活由 controller 负责，
 * 所以这里必须能看到"它没有试图自己去执行"。</p>
 */
class AgentConversationResumeTest {

    private static final long CONVERSATION_ID = 7L;
    private static final long USER_ID = 42L;
    private static final long RUN_ID = 17L;
    private static final long RUN_VERSION = 3L;

    private AgentConversationMapper conversationMapper;
    private AgentExecutionJournal executionJournal;
    private AgentRunLifecycle runLifecycle;

    private AgentConversationService service;

    @BeforeEach
    void setUp() {
        conversationMapper = mock(AgentConversationMapper.class);
        executionJournal = mock(AgentExecutionJournal.class);
        runLifecycle = mock(AgentRunLifecycle.class);
        service = new AgentConversationService(
                conversationMapper,
                mock(AgentMessageMapper.class),
                mock(AgentModelClient.class),
                new AgentToolRegistry(List.of()),
                new AgentConversationProperties(),
                mock(org.springframework.transaction.support.TransactionTemplate.class),
                mock(AgentEventService.class),
                mock(BillingService.class),
                new ObjectMapper(),
                mock(AgentPlanService.class),
                mock(AgentMemoryService.class),
                mock(AgentQuizService.class),
                executionJournal,
                mock(AgentRequestAssembler.class),
                runLifecycle,
                mock(AgentEventProjector.class),
                mock(AgentTurnCoordinator.class),
                mock(AgentSkillCatalog.class),
                mock(AgentCompactionService.class),
                new RunBudgetGuard());
    }

    private static AgentConversation conversation(Long owner) {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setUserId(owner);
        conversation.setStatus("interrupted");
        conversation.setRunVersion(RUN_VERSION);
        return conversation;
    }

    private static AgentRun run() {
        AgentRun run = new AgentRun();
        run.setId(RUN_ID);
        run.setConversationId(CONVERSATION_ID);
        run.setUserId(USER_ID);
        run.setRunVersion(RUN_VERSION);
        run.setStatus(AgentRun.STATUS_INTERRUPTED);
        return run;
    }

    @Test
    void resumeResolvesTheCurrentRunAndHandsItToTheLifecycle() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation(USER_ID));
        when(executionJournal.findCurrentRun(CONVERSATION_ID, RUN_VERSION))
                .thenReturn(Optional.of(run()));

        service.resume(CONVERSATION_ID, USER_ID);

        // 传下去的必须是**当前代次那条 Run 的 id**，而不是会话 id、也不是别的 Run。
        verify(runLifecycle).resume(CONVERSATION_ID, USER_ID, RUN_ID);
    }

    /**
     * 解析用的是"会话的当前代次"，不是"这个会话最新的一条 Run"。
     *
     * <p>两者在正常数据上一致，但会话的 {@code run_version} 才是权威——它是 {@code claim()}
     * 递增出来的，而 Run 表按 (会话, 代次) 唯一。查最新一条会绕开这个权威，
     * 于是在"新一轮刚 claim、Run 行还没写完"的窗口里解析到上一代次，
     * 恢复就落到了一个已经被顶替的执行上。</p>
     */
    @Test
    void resumeLooksUpTheRunByTheConversationsOwnGeneration() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation(USER_ID));
        when(executionJournal.findCurrentRun(CONVERSATION_ID, RUN_VERSION))
                .thenReturn(Optional.of(run()));

        service.resume(CONVERSATION_ID, USER_ID);

        verify(executionJournal).findCurrentRun(CONVERSATION_ID, RUN_VERSION);
    }

    @Test
    void resumeReturnsTheConversationRefreshedAfterTheTransition() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation(USER_ID));
        when(executionJournal.findCurrentRun(CONVERSATION_ID, RUN_VERSION))
                .thenReturn(Optional.of(run()));

        AgentConversation returned = service.resume(CONVERSATION_ID, USER_ID);

        assertThat(returned).isNotNull();
        // 必须在状态迁移**之后**重读，否则返回给前端的是恢复前的旧状态，前端会继续显示"已中断"。
        verify(conversationMapper, org.mockito.Mockito.times(2)).selectById(CONVERSATION_ID);
    }

    /** 执行记录没了要说成 409（业务拒绝），不能兜底成 500（服务器故障）。 */
    @Test
    void resumeRefusesWhenTheRunRecordIsGone() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation(USER_ID));
        when(executionJournal.findCurrentRun(CONVERSATION_ID, RUN_VERSION))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resume(CONVERSATION_ID, USER_ID))
                .isInstanceOf(AgentRunNotResumableException.class)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
        verify(runLifecycle, never()).resume(any(), any(), any());
    }

    /**
     * 不属于当前用户的会话必须在**碰任何东西之前**就被拒掉。
     *
     * <p>而且要是 404 而不是 403：403 等于确认"这个会话存在、只是不是你的"，
     * 那是把别人的会话 id 是否存在这件事泄露出去。</p>
     */
    @Test
    void resumeRefusesAnUnownedConversationWithoutTouchingTheRun() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation(999L));

        assertThatThrownBy(() -> service.resume(CONVERSATION_ID, USER_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(404));
        verify(executionJournal, never()).findCurrentRun(any(), anyLong());
        verify(runLifecycle, never()).resume(any(), any(), any());
    }

    @Test
    void resumeRefusesAMissingConversation() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.resume(CONVERSATION_ID, USER_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(404));
        verify(runLifecycle, never()).resume(any(), any(), any());
    }

    /** R-2 的拒绝（状态不可恢复）必须原样透出去，不能在这里被吞掉或改写。 */
    @Test
    void resumePropagatesTheLifecycleRefusal() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation(USER_ID));
        when(executionJournal.findCurrentRun(CONVERSATION_ID, RUN_VERSION))
                .thenReturn(Optional.of(run()));
        when(runLifecycle.resume(CONVERSATION_ID, USER_ID, RUN_ID))
                .thenThrow(new AgentRunNotResumableException(CONVERSATION_ID, RUN_VERSION,
                        "当前执行状态不可恢复"));

        assertThatThrownBy(() -> service.resume(CONVERSATION_ID, USER_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
    }

    /**
     * 恢复**不是**新一轮：绝不能 claim。
     *
     * <p>{@code claim()} 会递增 {@code run_version} 并铸新的 {@code intent_epoch}，
     * 于是"用户点继续"会变成"用户又说了一句新话"——原来那条中断的执行永远接不上，
     * 而它的 Effect 也不会再被采纳。这正是 R-1 要防的事，且它在端点上比在 reaper 上更容易犯：
     * "点继续"从字面上看很像"重新发起"。</p>
     */
    @Test
    void resumeNeverClaimsANewGeneration() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation(USER_ID));
        when(executionJournal.findCurrentRun(CONVERSATION_ID, RUN_VERSION))
                .thenReturn(Optional.of(run()));

        service.resume(CONVERSATION_ID, USER_ID);

        verify(runLifecycle, never()).claim(any(), any());
        // 也不该自己去执行：派活是 controller 的职责，service 在这里只改状态。
        verify(executionJournal, never()).startRun(any(), any(), anyLong(), any());
    }
}
