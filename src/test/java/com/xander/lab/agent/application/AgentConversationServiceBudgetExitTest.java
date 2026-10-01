package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentStep;
import com.xander.lab.agent.domain.RunUsage;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import com.xander.lab.agent.infrastructure.AgentConversationProperties;
import com.xander.lab.agent.infrastructure.AgentMessageMapper;
import com.xander.lab.agent.infrastructure.AgentModelClient;
import com.xander.lab.billing.application.BillingService;
import com.xander.lab.common.ModelCallResult;
import com.xander.lab.common.ModelUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预算耗尽的出口行为。
 *
 * <p>这里必须用完整构造器：旧兼容构造器把 {@link AgentExecutionJournal} 换成了
 * {@code disabled()} 实例，{@code nextStepNo} 恒为 1，"进入循环时就已经超预算"这条路径
 * 在它下面根本不可达。要钉住这个分支就得能控制步号。</p>
 */
@ExtendWith(MockitoExtension.class)
class AgentConversationServiceBudgetExitTest {

    private static final long CONVERSATION_ID = 7L;
    private static final long USER_ID = 42L;
    private static final long RUN_VERSION = 3L;

    @Mock
    private AgentConversationMapper conversationMapper;
    @Mock
    private AgentMessageMapper messageMapper;
    @Mock
    private AgentModelClient modelClient;
    @Mock
    private AgentEventService eventService;
    @Mock
    private BillingService billingService;
    @Mock
    private AgentPlanService planService;
    @Mock
    private AgentMemoryService memoryService;
    @Mock
    private AgentExecutionJournal executionJournal;
    @Mock
    private AgentRequestAssembler requestAssembler;
    @Mock
    private AgentRunLifecycle runLifecycle;
    @Mock
    private AgentEventProjector eventProjector;
    @Mock
    private AgentTurnCoordinator turnCoordinator;
    @Mock
    private AgentCompactionService compactionService;
    /**
     * 用 mock 包一层真 guard：默认把三个方法原样委派给真实实现（既有用例依赖它的步数判定），
     * 只在"某个维度耗尽"这类用例里替换成指定原因。这样既不用为了触发工具闸门去搭一套真实工具表，
     * 又能把**原因 → 文案**这段映射钉死。
     */
    @Mock
    private RunBudgetGuard runBudgetGuard;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AgentConversationProperties properties;
    private AgentConversationService service;

    @BeforeEach
    void setUp() {
        properties = new AgentConversationProperties();
        properties.setMaxSteps(20);
        properties.setMaxToolFailures(2);
        properties.setMaxParallelTools(4);
        properties.setContextMessageLimit(30);
        properties.setStaleConversationMinutes(30);
        service = new AgentConversationService(
                conversationMapper, messageMapper, modelClient, new AgentToolRegistry(List.of()), properties,
                null, eventService, billingService, objectMapper, planService, memoryService,
                new AgentQuizService(objectMapper), executionJournal, requestAssembler, runLifecycle,
                eventProjector, turnCoordinator, AgentSkillCatalog.empty(), compactionService,
                runBudgetGuard);

        RunBudgetGuard realGuard = new RunBudgetGuard();
        lenient().when(runBudgetGuard.budgetFrom(any()))
                .thenAnswer(invocation -> realGuard.budgetFrom(invocation.getArgument(0)));
        lenient().when(runBudgetGuard.check(any(), any()))
                .thenAnswer(invocation -> realGuard.check(invocation.getArgument(0), invocation.getArgument(1)));
        lenient().when(runBudgetGuard.check(any(), any(), anyInt()))
                .thenAnswer(invocation -> realGuard.check(invocation.getArgument(0),
                        invocation.getArgument(1), invocation.getArgument(2)));

        lenient().when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation());
        lenient().when(messageMapper.selectList(any())).thenReturn(List.of());
        lenient().when(planService.load(any())).thenReturn(List.of());
        lenient().when(memoryService.recall(any(), any(), anyInt())).thenReturn(List.of());
        lenient().when(memoryService.render(any())).thenReturn("");
        lenient().when(executionJournal.usageBeforeStep(anyLong(), anyLong(), anyInt()))
                .thenReturn(Optional.empty());
        // 检查点 1 每次都会聚合一次用量。这里给一个空值即可：**步数由循环自己的计数器提供**
        // （`used.withSteps(step - 1)`），不依赖这个 stub，所以超预算的路径仍然可达。
        lenient().when(executionJournal.loadUsage(anyLong(), anyLong())).thenReturn(RunUsage.EMPTY);
        lenient().when(compactionService.compactIfNeeded(any(), any(), anyLong(), any(), any(), any()))
                .thenReturn(new AgentCompactionService.Compacted(List.of(), ModelUsage.NONE, null));
        lenient().when(requestAssembler.assemble(any())).thenReturn(stepRequest());
        lenient().when(runLifecycle.isCancellationRequested(anyLong(), anyLong(), anyLong())).thenReturn(false);
        lenient().when(runLifecycle.complete(anyLong(), anyLong(), anyLong()))
                .thenReturn(new TerminalTransition(conversation(), null));
        // 预算耗尽现在走 BUDGET_EXHAUSTED 而不是 COMPLETED（见 B3）：run 状态与投影都不同，
        // 所以这里必须单独给 exhaustBudget 一个返回值，否则终态事件取不到会 NPE。
        lenient().when(runLifecycle.exhaustBudget(anyLong(), anyLong(), anyLong()))
                .thenReturn(new TerminalTransition(conversation(), null));
        lenient().when(eventProjector.appendActive(any(), any(), anyLong(), any(), any(), any()))
                .thenReturn(null);
        // 编排器是 mock，但它正是调用 runLoop 的那一层：不把工作体跑起来，被测的分支根本不会执行。
        lenient().when(turnCoordinator.execute(anyLong(), anyLong(), anyLong(), any(), any()))
                .thenAnswer(invocation -> invocation.<ClaimedTurnWork>getArgument(4).run());
    }

    /**
     * 审批恰好落在最后一步：那条调用已经执行完并落库，只是模型没机会说明。
     *
     * <p>修复前这里循环体一次都不进，用户收到的是"步数已达上限"——他会以为批准的操作没做，
     * 于是重发一次，而重发是一条新的 invocation，下游幂等键对不上，会真的执行第二遍。</p>
     */
    @Test
    void anApprovalLandingOnTheLastStepStillGetsOneClosingModelStep() {
        when(executionJournal.nextStepNo(CONVERSATION_ID, RUN_VERSION)).thenReturn(21);
        when(executionJournal.hasSettledInvocationInLatestStep(CONVERSATION_ID, RUN_VERSION)).thenReturn(true);
        ObjectNode done = JsonNodeFactory.instance.objectNode();
        done.put("done", true);
        done.put("reply", "你批准的操作已经完成，下面是执行结果。");
        when(turnCoordinator.executeModelStep(any(), any(), any()))
                .thenReturn(new ExecutedModelStep(new AgentStep(), ModelCallResult.of(done, ModelUsage.NONE)));

        service.executeClaimedTurn(CONVERSATION_ID, USER_ID, RUN_VERSION, "继续", false, ignored -> { });

        verify(turnCoordinator, times(1)).executeModelStep(any(), any(), any());
        assertThat(savedAssistantContent()).isEqualTo("你批准的操作已经完成，下面是执行结果。");
    }

    /**
     * 同样是"进入时就已超预算"，但没有已提交的调用要交代——不该白送一步。
     *
     * <p>这条钉住 {@code hasSettledInvocationInLatestStep} 这个闸门本身：
     * 少了它，任何一次越界的重入都会多烧一次模型调用。</p>
     */
    @Test
    void enteringPastBudgetWithoutASettledCallStillPausesImmediately() {
        when(executionJournal.nextStepNo(CONVERSATION_ID, RUN_VERSION)).thenReturn(21);
        when(executionJournal.hasSettledInvocationInLatestStep(CONVERSATION_ID, RUN_VERSION)).thenReturn(false);

        service.executeClaimedTurn(CONVERSATION_ID, USER_ID, RUN_VERSION, "继续", false, ignored -> { });

        verify(turnCoordinator, never()).executeModelStep(any(), any(), any());
        assertThat(savedAssistantContent()).contains("推理步数已达上限");
    }

    /** 纯粹的自然耗尽：没有任何已提交的副作用，文案不该暗示"你批准的操作已经执行"。 */
    @Test
    void naturalStepExhaustionReportsAPlainPause() {
        properties.setMaxSteps(1);
        when(executionJournal.nextStepNo(CONVERSATION_ID, RUN_VERSION)).thenReturn(1);
        ObjectNode action = JsonNodeFactory.instance.objectNode();
        action.put("done", false);
        action.putObject("action").put("tool", "并不存在的工具");
        when(turnCoordinator.executeModelStep(any(), any(), any()))
                .thenReturn(new ExecutedModelStep(new AgentStep(), ModelCallResult.of(action, ModelUsage.NONE)));

        service.executeClaimedTurn(CONVERSATION_ID, USER_ID, RUN_VERSION, "随便做点什么", false, ignored -> { });

        verify(turnCoordinator, times(1)).executeModelStep(any(), any(), any());
        assertThat(savedAssistantContent())
                .contains("推理步数已达上限")
                .doesNotContain("你批准的操作已经执行完成");
    }

    /**
     * 耗尽项必须各自对应一句话，而不是统一一句"预算用尽"。
     *
     * <p>塌成一句的后果是用户唯一能做的就是重发——而重发在"步数用完"下是对的、
     * 在"积分用完"下毫无用处。所以这里逐个钉：加一个耗尽项就必须同时给一句话。</p>
     */
    @Test
    void toolCallExhaustionSaysToolCallsRatherThanSteps() {
        when(executionJournal.nextStepNo(CONVERSATION_ID, RUN_VERSION)).thenReturn(1);
        when(runBudgetGuard.check(any(), any()))
                .thenReturn(RunBudgetGuard.Verdict.exhausted(RunBudgetGuard.Reason.MAX_TOOL_CALLS));

        service.executeClaimedTurn(CONVERSATION_ID, USER_ID, RUN_VERSION, "继续", false, ignored -> { });

        verify(turnCoordinator, never()).executeModelStep(any(), any(), any());
        assertThat(savedAssistantContent())
                .contains("工具调用次数已达上限")
                .doesNotContain("推理步数已达上限");
    }

    /** 时长文案必须点明"等待你确认的时间不计入"，否则用户会以为自己挂机把预算耗光了。 */
    @Test
    void activeDurationExhaustionExplainsThatWaitingIsNotCounted() {
        when(executionJournal.nextStepNo(CONVERSATION_ID, RUN_VERSION)).thenReturn(1);
        when(runBudgetGuard.check(any(), any()))
                .thenReturn(RunBudgetGuard.Verdict.exhausted(RunBudgetGuard.Reason.MAX_ACTIVE_DURATION));

        service.executeClaimedTurn(CONVERSATION_ID, USER_ID, RUN_VERSION, "继续", false, ignored -> { });

        verify(turnCoordinator, never()).executeModelStep(any(), any(), any());
        assertThat(savedAssistantContent())
                .contains("执行时长已达上限")
                .contains("等待你确认的时间不计入");
    }

    @Test
    void moneyExhaustionSaysPointsRatherThanTokens() {
        when(executionJournal.nextStepNo(CONVERSATION_ID, RUN_VERSION)).thenReturn(1);
        when(runBudgetGuard.check(any(), any()))
                .thenReturn(RunBudgetGuard.Verdict.exhausted(RunBudgetGuard.Reason.MAX_MILLI_POINTS));

        service.executeClaimedTurn(CONVERSATION_ID, USER_ID, RUN_VERSION, "继续", false, ignored -> { });

        assertThat(savedAssistantContent())
                .contains("积分已达上限")
                .doesNotContain("模型用量已达上限");
    }

    private String savedAssistantContent() {
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(eventProjector).saveActiveMessage(
                eq(CONVERSATION_ID), eq(USER_ID), eq(RUN_VERSION), eq("assistant"), eq("answer"), any(), content.capture());
        return content.getValue();
    }

    private AgentStepRequest stepRequest() {
        return new AgentStepRequest(CONVERSATION_ID, USER_ID, RUN_VERSION, 1, List.of(), "", List.of(),
                List.of(), List.of(), "hash", "route", "instruction-set", 1, false, "{}");
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
