package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.agent.domain.AgentApproval;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentToolInvocation;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import com.xander.lab.agent.infrastructure.AgentConversationProperties;
import com.xander.lab.agent.infrastructure.AgentMessageMapper;
import com.xander.lab.agent.infrastructure.AgentModelClient;
import com.xander.lab.billing.application.BillingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审批恢复路径上的副作用边界标记。
 *
 * <p>这条路径**绕开了 {@link AgentToolExecutionPipeline}**：它直接执行那条已被批准的调用，
 * 所以管道里那处 {@code markEffectDispatched} 覆盖不到它。两处都标是必需的——漏掉任何一处，
 * 该路径上的崩溃都会被崩溃收口当成"没越过边界"而静默终结，于是一次可能已经写出去的调用
 * 被记成已终结、不再进对账，用户重发就是第二次写。</p>
 *
 * <p>之所以值得单开一个测试类：这个缺陷**只在崩溃时才显形**，没有测试的话它连编译期都不会
 * 被发现；而它所在的路径又被审批这一层挡在日常手工验证之外。</p>
 */
class AgentApprovedInvocationResumeTest {

    private static final long CONVERSATION_ID = 7L;
    private static final long USER_ID = 42L;
    private static final long RUN_ID = 17L;
    private static final long RUN_VERSION = 3L;
    private static final String TOOL = "publish_post";

    private AgentConversationMapper conversationMapper;
    private AgentExecutionJournal executionJournal;
    private AgentRunLifecycle runLifecycle;

    @BeforeEach
    void setUp() {
        conversationMapper = mock(AgentConversationMapper.class);
        executionJournal = mock(AgentExecutionJournal.class);
        runLifecycle = mock(AgentRunLifecycle.class);
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(runningConversation());
        when(runLifecycle.isCancellationRequested(CONVERSATION_ID, USER_ID, RUN_VERSION)).thenReturn(false);
    }

    /**
     * 标记必须写在把请求交给工具**之前**。
     *
     * <p>顺序不能反：崩在"标记"与"发送"之间只会留下一个假 {@code DISPATCHED}（多一次无谓对账，
     * 安全方向）；反过来则会漏掉一次真实写入（要命方向）。工具在这里刻意抛异常——
     * 那不影响这条断言，因为"标记早于交接"与工具成功与否无关；而抛异常能让方法在
     * {@code failApprovedInvocation} 处收口，不必再把整轮编排跑起来。</p>
     */
    @Test
    void theDispatchMarkIsWrittenBeforeTheApprovedToolRuns() {
        AtomicBoolean dispatched = new AtomicBoolean();
        AtomicBoolean dispatchedWhenExecuting = new AtomicBoolean();
        doAnswer(ignored -> {
            dispatched.set(true);
            return null;
        }).when(executionJournal).markEffectDispatched(any());
        AgentToolInvocation invocation = approvedInvocation();
        AgentConversationService service = serviceWith(toolObserving(dispatched, dispatchedWhenExecuting));

        service.resumeApprovedInvocation(approval(), invocation);

        assertThat(dispatchedWhenExecuting.get())
                .as("工具开跑时应当已经越过副作用边界")
                .isTrue();
        verify(executionJournal).markEffectDispatched(invocation);
    }

    @Test
    void theDispatchMarkIsWrittenBeforeTheFailureIsRecorded() {
        // 失败收口也不能抢在标记之前：否则一条"请求已经发出、远端可能已写"的调用会被记成
        // 一个干净的失败，对账器再也不会看它。
        doAnswer(ignored -> null).when(executionJournal).markEffectDispatched(any());
        AgentToolInvocation invocation = approvedInvocation();
        AgentConversationService service = serviceWith(toolObserving(new AtomicBoolean(),
                new AtomicBoolean()));

        service.resumeApprovedInvocation(approval(), invocation);

        InOrder order = inOrder(executionJournal);
        order.verify(executionJournal).markEffectDispatched(invocation);
        order.verify(executionJournal).failInvocation(any(), any());
    }

    @Test
    void aCancelledApprovalNeverCrossesTheBoundary() {
        // 取消检查排在标记之前：取消已经请求时我们根本不会发，也就不该留下 DISPATCHED——
        // 那会给对账器送去一次纯属虚构的远端查询。
        when(runLifecycle.isCancellationRequested(CONVERSATION_ID, USER_ID, RUN_VERSION)).thenReturn(true);
        AgentToolInvocation invocation = approvedInvocation();
        AgentConversationService service = serviceWith(toolObserving(new AtomicBoolean(),
                new AtomicBoolean()));

        service.resumeApprovedInvocation(approval(), invocation);

        verify(executionJournal, org.mockito.Mockito.never()).markEffectDispatched(any());
    }

    /** 记录"开跑时是否已经标记过"，然后抛异常让方法就地收口。 */
    private AgentTool toolObserving(AtomicBoolean dispatched, AtomicBoolean dispatchedWhenExecuting) {
        return new AgentTool() {
            @Override public String name() { return TOOL; }
            @Override public String description() { return TOOL; }
            @Override public boolean readOnly() { return false; }
            @Override public JsonNode parametersSchema() { return JsonNodeFactory.instance.objectNode(); }
            @Override public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
                dispatchedWhenExecuting.set(dispatched.get());
                throw new IllegalStateException("远端炸了");
            }
        };
    }

    private AgentConversationService serviceWith(AgentTool tool) {
        return new AgentConversationService(
                conversationMapper,
                mock(AgentMessageMapper.class),
                mock(AgentModelClient.class),
                new AgentToolRegistry(List.of(tool)),
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

    private static AgentApproval approval() {
        AgentApproval approval = new AgentApproval();
        approval.setId(5L);
        approval.setUserId(USER_ID);
        approval.setConversationId(CONVERSATION_ID);
        approval.setRunId(RUN_ID);
        approval.setToolName(TOOL);
        return approval;
    }

    private static AgentToolInvocation approvedInvocation() {
        AgentToolInvocation invocation = new AgentToolInvocation();
        invocation.setId(29L);
        invocation.setRunId(RUN_ID);
        invocation.setInvocationId("invocation-29");
        invocation.setEffectId("effect-1");
        invocation.setToolName(TOOL);
        invocation.setArgumentsJson("{}");
        invocation.setStatus(AgentToolInvocation.STATUS_WAITING_APPROVAL);
        return invocation;
    }

    private static AgentConversation runningConversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setUserId(USER_ID);
        conversation.setRunVersion(RUN_VERSION);
        conversation.setStatus("running");
        return conversation;
    }
}
