package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentApproval;
import com.xander.lab.agent.domain.AgentEvent;
import com.xander.lab.agent.domain.AgentInputTurn;
import com.xander.lab.agent.domain.AgentMessage;
import com.xander.lab.agent.domain.AgentPlanItem;
import com.xander.lab.agent.domain.AgentToolInvocation;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import com.xander.lab.agent.infrastructure.AgentConversationProperties;
import com.xander.lab.agent.infrastructure.AgentEventMapper;
import com.xander.lab.agent.infrastructure.AgentMessageMapper;
import com.xander.lab.agent.infrastructure.AgentModelClient;
import com.xander.lab.billing.application.BillingService;
import com.xander.lab.common.ModelCallResult;
import com.xander.lab.common.ModelUsage;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentConversationServiceTest {

    @Mock
    private AgentConversationMapper conversationMapper;
    @Mock
    private AgentMessageMapper messageMapper;
    @Mock
    private AgentModelClient modelClient;
    @Mock
    private AgentEventMapper eventMapper;
    @Mock
    private AgentConversationProperties properties;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private ScheduledExecutorService agentLeaseRenewer;
    @Mock
    private ScheduledFuture<?> leaseRenewal;
    @Mock
    private AgentPlanService planService;
    @Mock
    private AgentMemoryService memoryService;

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    private final BillingService billingService = mock(BillingService.class);
    private AgentEventService eventService;

    private AgentToolRegistry registry;
    private AgentConversationService service;

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AgentConversation.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AgentMessage.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AgentEvent.class);
    }

    @BeforeEach
    void setUp() {
        registry = new AgentToolRegistry(List.of(new StubTool()));
        AtomicInteger eventIds = new AtomicInteger();
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            AgentEvent event = invocation.getArgument(0);
            event.setId((long) eventIds.incrementAndGet());
            return 1;
        }).when(eventMapper).insertForRun(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        org.mockito.Mockito.lenient().when(messageMapper.insertForActiveRun(any(), any(), any(Long.class)))
                .thenReturn(1);
        org.mockito.Mockito.lenient().when(eventMapper.selectList(any())).thenReturn(List.of());
        eventService = new AgentEventService(eventMapper, conversationMapper, objectMapper, new AgentEventBus());
        service = new AgentConversationService(
                conversationMapper, messageMapper, modelClient, registry, properties,
                transactionTemplate, eventService, agentLeaseRenewer, billingService, objectMapper,
                planService, memoryService, new AgentQuizService(objectMapper));
        org.mockito.Mockito.lenient().doReturn(leaseRenewal).when(agentLeaseRenewer)
                .scheduleAtFixedRate(any(Runnable.class), any(Long.class), any(Long.class), any());
        org.mockito.Mockito.lenient().when(properties.getMaxSteps()).thenReturn(10);
        org.mockito.Mockito.lenient().when(properties.getMaxToolFailures()).thenReturn(2);
        org.mockito.Mockito.lenient().when(properties.getMaxParallelTools()).thenReturn(4);
        org.mockito.Mockito.lenient().when(properties.getContextMessageLimit()).thenReturn(30);
        org.mockito.Mockito.lenient().when(properties.getStaleConversationMinutes()).thenReturn(30);
    }

    @Test
    void modelDecidesToCallToolThenFinish() {
        stubTransactions();
        ObjectNode first = decisionNode(false, "需要调用博客生成工具", "blog_generate", "帮我写博客");
        ObjectNode second = decisionNode(true, "文章已生成", null, null);
        second.put("reply", "已完成，博客文章已生成");
        when(modelClient.decideNextStepStream(any(), any(), any(), any()))
                .thenReturn(call(first), call(second));

        AtomicInteger progressEvents = new AtomicInteger();
        AtomicInteger deltaEvents = new AtomicInteger();
        AgentConversation ready = conversation("running");
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(ready);

        AgentConversation result = service.executeTurn(7L, 42L, "帮我写一篇博客", event -> {
            if ("tool_progress".equals(event.getEventType())) progressEvents.incrementAndGet();
            if ("tool_delta".equals(event.getEventType())) {
                deltaEvents.incrementAndGet();
                assertThat(event.getDataContent()).contains("draft-tail");
            }
        });

        assertThat(result.getStatus()).isEqualTo("ready");
        assertThat(progressEvents.get()).isEqualTo(1);
        assertThat(deltaEvents.get()).isEqualTo(1);
        verify(messageMapper).insertForActiveRun(
                argThatMessage("tool", "tool_call", "blog_generate"), eq(42L), eq(1L));
        verify(messageMapper).insertForActiveRun(
                argThatMessage("assistant", "answer", null), eq(42L), eq(1L));
    }

    /**
     * thought 是每步实时推给前端的，所以它必须是一句给用户看的进度，不是模型的内部推理。
     *
     * <p>回归：用户发"给我一个sse代码"，界面上出现了模型的原始思考——连内部工具契约一起摊给了用户。
     * 提示词已经改口径了，这里守的是即使模型不听话也不会漏出去（见 {@link AgentThoughtSanitizer}）。</p>
     */
    @Test
    void rawReasoningInThoughtNeverReachesTheUser() {
        stubTransactions();
        ObjectNode leaking = decisionNode(false,
                "用户需要可运行的 SSE 示例，且是多文件代码，必须通过 artifact 工具交付。"
                        + "准备提供一个零依赖的 Node.js SSE 服务端和浏览器客户端。",
                "blog_generate", "sse 示例");
        ObjectNode done = decisionNode(true, "", null, null);
        done.put("reply", "给你一段 SSE 示例");
        when(modelClient.decideNextStepStream(any(), any(), any(), any()))
                .thenReturn(call(leaking), call(done));
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));

        List<AgentEvent> events = new ArrayList<>();
        service.executeTurn(7L, 42L, "给我一个sse代码", events::add);

        assertThat(events).noneMatch(event -> "thought".equals(event.getEventType()));
        assertThat(events).noneMatch(event -> String.valueOf(event.getDataContent()).contains("artifact"));
        verify(messageMapper, org.mockito.Mockito.never()).insertForActiveRun(
                argThatMessage("assistant", "thought", null), eq(42L), eq(1L));
    }

    @Test
    void toolFailureIsFedBackAndTerminatesAfterLimit() {
        stubTransactions();
        org.mockito.Mockito.when(properties.getMaxToolFailures()).thenReturn(1);
        ObjectNode failing = decisionNode(false, "调用工具", "failing_tool", null);
        when(modelClient.decideNextStepStream(any(), any(), any(), any())).thenReturn(call(failing));
        AgentToolRegistry failingRegistry = new AgentToolRegistry(
                List.of(new FailingTool("failing_tool")));
        service = new AgentConversationService(
                conversationMapper, messageMapper, modelClient, failingRegistry, properties,
                transactionTemplate, eventService, agentLeaseRenewer, billingService, objectMapper,
                planService, memoryService, new AgentQuizService(objectMapper));
        AgentConversation ready = conversation("running");
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(ready);

        AgentConversation result = service.executeTurn(7L, 42L, "触发失败", ignored -> { });

        assertThat(result.getStatus()).isEqualTo("ready");
        verify(modelClient).decideNextStepStream(any(), any(), any(), any());
    }

    @Test
    void aBlankToolNameIsFedBackAsAFailureInsteadOfBreakingTheWholeTurn() {
        stubTransactions();
        org.mockito.Mockito.when(properties.getMaxToolFailures()).thenReturn(1);
        ObjectNode blank = decisionNode(false, "想调工具但没写工具名", "", null);
        when(modelClient.decideNextStepStream(any(), any(), any(), any())).thenReturn(call(blank));
        AgentConversation ready = conversation("running");
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(ready);

        AgentConversation result = service.executeTurn(7L, 42L, "考考我这条知识", ignored -> { });

        assertThat(result.getStatus()).isEqualTo("ready");
        verify(messageMapper).insertForActiveRun(
                argThatMessage("assistant", "answer", null), eq(42L), eq(1L));
    }

    /**
     * 一步多个互不依赖的查询：模型一次报出来，服务端一次全执行完，
     * 而不是让它分三步、每次只问一件事——那会把 {@code maxSteps} 白白烧在往返上。
     */
    @Test
    void aBatchOfIndependentQueriesIsExecutedInASingleStep() {
        stubTransactions();
        ReadOnlyStubTool first = new ReadOnlyStubTool("read_a");
        ReadOnlyStubTool second = new ReadOnlyStubTool("read_b");
        service = serviceWith(first, second);
        ObjectNode batch = batchDecision("同时查知识点和文章", "read_a", "read_b");
        ObjectNode done = decisionNode(true, "", null, null);
        done.put("reply", "两件都查完了");
        when(modelClient.decideNextStepStream(any(), any(), any(), any()))
                .thenReturn(call(batch), call(done));
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));

        AgentConversation result = service.executeTurn(7L, 42L, "帮我看看我的知识点和文章", ignored -> { });

        assertThat(result.getStatus()).isEqualTo("ready");
        assertThat(first.executions.get()).isEqualTo(1);
        assertThat(second.executions.get()).isEqualTo(1);
        // 一次决策报两个调用 → 整轮只需两次模型往返（这一次 + 收口），不是三次
        verify(modelClient, org.mockito.Mockito.times(2)).decideNextStepStream(any(), any(), any(), any());
        verify(messageMapper).insertForActiveRun(argThatMessage("tool", "tool_call", "read_a"), eq(42L), eq(1L));
        verify(messageMapper).insertForActiveRun(argThatMessage("tool", "tool_call", "read_b"), eq(42L), eq(1L));
        verify(messageMapper).insertForActiveRun(argThatMessage("tool", "tool_result", "read_a"), eq(42L), eq(1L));
        verify(messageMapper).insertForActiveRun(argThatMessage("tool", "tool_result", "read_b"), eq(42L), eq(1L));
    }

    /**
     * 同名但参数不同的两次调用是合法用法（一次查两个主题的知识点），而且必须真的跑两次。
     *
     * <p>这条守着并行批次的收益：早先按工具名配对时同名会被整批拒掉，模型只能一步步来。
     * 现在每次调用各有 invocationId，两张卡不会串。</p>
     */
    @Test
    void theSameToolWithDifferentArgumentsRunsTwiceInOneStep() {
        stubTransactions();
        ReadOnlyStubTool repeated = new ReadOnlyStubTool("read_a");
        service = serviceWith(repeated);
        ObjectNode batch = batchDecisionWithTopics("查两个主题", "read_a", "主题一", "主题二");
        ObjectNode done = decisionNode(true, "", null, null);
        done.put("reply", "两个都查完了");
        when(modelClient.decideNextStepStream(any(), any(), any(), any()))
                .thenReturn(call(batch), call(done));
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));

        service.executeTurn(7L, 42L, "查两个主题", ignored -> { });

        assertThat(repeated.executions.get()).isEqualTo(2);
        List<AgentMessage> messages = capturedMessages();
        // 两次调用两个身份：这是前端把两张卡分开的唯一依据。
        assertThat(invocationIdsOf(messages, "tool_call", "read_a"))
                .hasSize(2)
                .doesNotContainNull()
                .doesNotHaveDuplicates();
        assertThat(invocationIdsOf(messages, "tool_result", "read_a"))
                .isEqualTo(invocationIdsOf(messages, "tool_call", "read_a"));
    }

    /**
     * 同名**且**同参数是笔误（写操作还会被执行两次），整批退回并回灌纠正，
     * 而不是"跑一半"——跑一半模型分不清哪一半生效。
     */
    @Test
    void aBatchThatRepeatsTheExactSameCallIsRejectedWithoutRunningAnything() {
        stubTransactions();
        ReadOnlyStubTool repeated = new ReadOnlyStubTool("read_a");
        service = serviceWith(repeated);
        ObjectNode batch = batchDecisionWithTopics("查两次", "read_a", "同一个", "同一个");
        ObjectNode done = decisionNode(true, "", null, null);
        done.put("reply", "换个方式再来");
        when(modelClient.decideNextStepStream(any(), any(), any(), any()))
                .thenReturn(call(batch), call(done));
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));

        service.executeTurn(7L, 42L, "查两次", ignored -> { });

        assertThat(repeated.executions.get()).isZero();
        verify(messageMapper, org.mockito.Mockito.never()).insertForActiveRun(
                argThatMessage("tool", "tool_call", "read_a"), eq(42L), eq(1L));
    }

    /**
     * 超出单步上限的调用必须留下"未执行"的记录。静默丢弃的后果不是少一次调用，
     * 而是模型以为自己问过、只是没收到答案，于是拿着残缺信息收口。
     */
    @Test
    void callsBeyondThePerStepCapAreReportedAsNotExecutedRatherThanDropped() {
        stubTransactions();
        when(properties.getMaxParallelTools()).thenReturn(1);
        ReadOnlyStubTool first = new ReadOnlyStubTool("read_a");
        ReadOnlyStubTool second = new ReadOnlyStubTool("read_b");
        service = serviceWith(first, second);
        ObjectNode batch = batchDecision("一次问两件事", "read_a", "read_b");
        ObjectNode done = decisionNode(true, "", null, null);
        done.put("reply", "先看到第一件的结果");
        when(modelClient.decideNextStepStream(any(), any(), any(), any()))
                .thenReturn(call(batch), call(done));
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));

        service.executeTurn(7L, 42L, "一次问两件事", ignored -> { });

        assertThat(first.executions.get()).isEqualTo(1);
        assertThat(second.executions.get()).isZero();
        verify(messageMapper).insertForActiveRun(argThatMessage("tool", "tool_result", "read_b"), eq(42L), eq(1L));
    }

    /**
     * 一次调用的入参和结果必须带上**同一个** invocationId，不同调用必须不同。
     *
     * <p>前端就是靠这两条性质把卡片合对的（见 agentTrace.js 的 mergeToolTraces）：
     * 同值才能认出"这两条消息是同一次调用"，不同值才能把两个同名工具分开。少任何一条，
     * 并行批次里同名的两次调用就会串成一张卡。</p>
     */
    @Test
    void eachToolMessageCarriesTheInvocationIdThatPairsItsCallWithItsResult() {
        stubTransactions();
        ReadOnlyStubTool first = new ReadOnlyStubTool("read_a");
        ReadOnlyStubTool second = new ReadOnlyStubTool("read_b");
        service = serviceWith(first, second);
        ObjectNode batch = batchDecision("同时查两件事", "read_a", "read_b");
        ObjectNode done = decisionNode(true, "", null, null);
        done.put("reply", "两件都查完了");
        when(modelClient.decideNextStepStream(any(), any(), any(), any()))
                .thenReturn(call(batch), call(done));
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));

        service.executeTurn(7L, 42L, "同时查两件事", ignored -> { });

        List<AgentMessage> messages = capturedMessages();
        List<String> callA = invocationIdsOf(messages, "tool_call", "read_a");
        List<String> resultA = invocationIdsOf(messages, "tool_result", "read_a");
        List<String> callB = invocationIdsOf(messages, "tool_call", "read_b");
        List<String> resultB = invocationIdsOf(messages, "tool_result", "read_b");

        assertThat(callA).hasSize(1).doesNotContainNull();
        assertThat(resultA).isEqualTo(callA);
        assertThat(resultB).isEqualTo(callB);
        assertThat(callA).doesNotContainAnyElementsOf(callB);
    }

    /**
     * 工具事件带身份，落库正文**不带**。
     *
     * <p>两半都是要点，而且是相反的：事件少了身份，前端只能回退到按工具名配对，同名工具就串卡；
     * 正文里多了身份，它会被原样渲染进提示词（「（调用了工具）」「【工具结果】」），
     * 让模型每一步都多看一个它用不上的 UUID。身份只住在 {@code agent_message.invocation_id} 这一列上。</p>
     */
    @Test
    void toolEventsCarryTheInvocationIdWhileThePersistedContentStaysClean() throws Exception {
        stubTransactions();
        ObjectNode first = decisionNode(false, "需要调用博客生成工具", "blog_generate", "帮我写博客");
        ObjectNode second = decisionNode(true, "", null, null);
        second.put("reply", "已完成");
        when(modelClient.decideNextStepStream(any(), any(), any(), any()))
                .thenReturn(call(first), call(second));
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));

        List<String> eventTypes = new ArrayList<>();
        java.util.Map<String, String> payloadByEvent = new java.util.LinkedHashMap<>();
        service.executeTurn(7L, 42L, "帮我写一篇博客", event -> {
            if (!event.getEventType().startsWith("tool_")) return;
            eventTypes.add(event.getEventType());
            payloadByEvent.put(event.getEventType(), event.getDataContent());
        });

        assertThat(eventTypes).containsExactly("tool_start", "tool_progress", "tool_delta", "tool_end");
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (String payload : payloadByEvent.values()) {
            String id = objectMapper.readTree(payload).path("invocationId").asText("");
            assertThat(id).as("每个 tool_* 事件都要带身份：%s", payload).isNotBlank();
            ids.add(id);
        }
        // 四个阶段是同一个身份：前端靠它把四条事件合成一张卡。
        assertThat(ids).hasSize(1);

        String invocationId = ids.iterator().next();
        for (AgentMessage message : capturedMessages()) {
            if (!"tool_call".equals(message.getKind()) && !"tool_result".equals(message.getKind())) continue;
            assertThat(message.getInvocationId()).isEqualTo(invocationId);
            assertThat(message.getContent()).doesNotContain(invocationId);
        }
    }

    /**
     * 回归：主循环必须真的能把"需要审批的调用"路由到审批挂起。
     *
     * <p>这条守着一个真实的自伤。批次化改造后编排循环只走 {@code executeBatch}，而它当时对
     * 需要审批的调用**一律**抛 {@code IllegalStateException}；单调用路径
     * （{@code executeTool → execute}）虽然还留着审批逻辑，却已经没有调用点了。结果是
     * {@code AgentApprovalRequiredException} 在生产代码里永远抛不出来——审批功能整个失效，
     * 用户看到的是一句"连续多次尝试后仍然失败：需要审批的工具必须逐个串行执行"。</p>
     *
     * <p>既有的单测抓不到它：那条路径自己的测试还在跑、还是绿的，只是没人调用它。
     * 所以要在这里用**完整**执行链跑一遍，而不是用简化构造器。</p>
     */
    @Test
    void anApprovalRequiredToolPausesTheTurnInsteadOfFailing() {
        stubTransactions();
        ApprovalStubTool gated = new ApprovalStubTool("publish_post");
        service = serviceWithApproval(gated);
        ObjectNode decision = decisionNode(false, "要发布文章", "publish_post", "内容");
        when(modelClient.decideNextStepStream(any(), any(), any(), any())).thenReturn(call(decision));
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));

        List<String> events = new ArrayList<>();
        service.executeTurn(7L, 42L, "帮我发布", event -> events.add(event.getEventType()));

        // 用户看到的信号是"需要你批准"，而不是"失败了"。
        assertThat(events).contains("approval_required").doesNotContain("error");
        assertThat(gated.executions.get()).isZero();
        // 挂起前 tool_call 必须已经落库：批准后恢复的就是这条 invocation，界面上也要看得到在等什么。
        verify(messageMapper).insertForActiveRun(
                argThatMessage("tool", "tool_call", "publish_post"), eq(42L), eq(1L));
        // 挂起不是失败：不能顺手写一条 tool_result，否则刷新后这张卡看起来像"已经跑完了"。
        verify(messageMapper, org.mockito.Mockito.never()).insertForActiveRun(
                argThatMessage("tool", "tool_result", "publish_post"), eq(42L), eq(1L));
    }

    /**
     * 多调用批次里混进需要审批的调用：整批退回，一个都不执行，并且把原因回灌给模型。
     *
     * <p>不能"跑一半再等审批"——批准之后没法表达"只执行那一半"，整批重来又会让前一半重复执行
     * （写操作重复就是事故）。所以让模型把那一个单独发起；提示词本来就要求写操作一次只做一件。</p>
     */
    @Test
    void aBatchMixingAnApprovalRequiredCallIsRejectedWithoutRunningAnything() {
        stubTransactions();
        ApprovalStubTool gated = new ApprovalStubTool("publish_post");
        ReadOnlyStubTool readOnly = new ReadOnlyStubTool("read_a");
        service = serviceWithApproval(gated, readOnly);
        ObjectNode batch = batchDecision("顺手一起做了", "read_a", "publish_post");
        ObjectNode done = decisionNode(true, "", null, null);
        done.put("reply", "分开来做");
        when(modelClient.decideNextStepStream(any(), any(), any(), any()))
                .thenReturn(call(batch), call(done));
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));

        service.executeTurn(7L, 42L, "顺手一起做了", ignored -> { });

        assertThat(readOnly.executions.get()).isZero();
        assertThat(gated.executions.get()).isZero();
        // 整批在开单之前就被拒，所以连只读那条也不该留下 tool_call。
        verify(messageMapper, org.mockito.Mockito.never()).insertForActiveRun(
                argThatMessage("tool", "tool_call", "read_a"), eq(42L), eq(1L));
        // 拒绝必须说清怎么办，否则模型只会原样重试同一批，把失败预算烧完。
        assertThat(secondRequestText()).contains("审批").contains("单独调用");
    }

    /** 第二次模型请求的提示词正文，用来断言"回灌给模型的纠正说明"。 */
    private String secondRequestText() {
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<AgentInputTurn>> turns =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(modelClient, org.mockito.Mockito.times(2))
                .decideNextStepStream(turns.capture(), any(), any(), any());
        return turns.getAllValues().get(1).stream()
                .map(AgentInputTurn::text)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    @Test
    void duplicateTurnWhileRunningIsRejected() {
        stubTransactions();
        when(conversationMapper.update(any(), any())).thenReturn(0);
        AgentConversation running = conversation("running");
        when(conversationMapper.selectById(7L)).thenReturn(running);

        assertThatThrownBy(() -> service.executeTurn(7L, 42L, "再来一轮", ignored -> { }))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("执行中");
        org.mockito.Mockito.verifyNoInteractions(modelClient);
    }

    @Test
    void staleRunningConversationIsRestoredToFailed() {
        stubTransactions();
        AgentConversation stale = conversation("running");
        stale.setUpdatedAt(LocalDateTime.now().minusMinutes(31));
        AgentConversation recovered = conversation("failed");
        recovered.setErrorMessage("上一轮执行中断，可继续对话");
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(recovered);

        AgentConversation result = service.resolveStale(stale, 42L);

        assertThat(result.getStatus()).isEqualTo("failed");
        assertThat(result.getErrorMessage()).contains("中断");
    }

    @Test
    void createUsesInitialMessageAsTitleWithoutPersistingIt() {
        when(conversationMapper.insert(any())).thenReturn(1);

        AgentConversation result = service.create(42L, "帮我生成一篇雨后校园作文");

        assertThat(result.getTitle()).isEqualTo("帮我生成一篇雨后校园作文");
        assertThat(result.getStatus()).isEqualTo("ready");
        assertThat(result.getRunVersion()).isEqualTo(1L);
        org.mockito.Mockito.verifyNoInteractions(messageMapper);
    }

    @Test
    void prepareTurnSetsTitleFromFirstUserMessageOnReadyShell() {
        stubTransactions();
        AgentConversation ready = conversation("ready");
        ready.setTitle("新对话");
        when(conversationMapper.selectById(7L)).thenReturn(ready);
        when(conversationMapper.update(any(), any())).thenReturn(1);

        AgentConversation claimed = service.prepareTurn(7L, 42L, "帮我写一篇关于 React 的文章");

        assertThat(claimed.getTitle()).isEqualTo("帮我写一篇关于 React 的文章");
        verify(messageMapper).insert(argThatMessage("user", "message", null));
        verify(conversationMapper).updateById(ready);
    }

    @Test
    void prepareTurnKeepsExistingTitleUntouched() {
        stubTransactions();
        AgentConversation existing = conversation("ready");
        existing.setTitle("已有标题");
        when(conversationMapper.selectById(7L)).thenReturn(existing);
        when(conversationMapper.update(any(), any())).thenReturn(1);

        service.prepareTurn(7L, 42L, "新消息");

        org.mockito.Mockito.verify(conversationMapper, org.mockito.Mockito.never()).updateById(any());
    }

    @Test
    void anAnswerCardIsPersistedAsAMessageSoItSurvivesAPageReload() {
        // 答题卡是用户接下来要操作的界面：只发事件的话刷新一次就没了，必须同时落一条消息。
        stubTransactions();
        registry = new AgentToolRegistry(List.of(new EmitQuizTool()));
        service = new AgentConversationService(
                conversationMapper, messageMapper, modelClient, registry, properties,
                transactionTemplate, eventService, agentLeaseRenewer, billingService, objectMapper,
                planService, memoryService, new AgentQuizService(objectMapper));
        ObjectNode emit = decisionNode(false, "先把题目发出去", EmitQuizTool.NAME, null);
        ObjectNode args = ((ObjectNode) emit.path("action")).putObject("args");
        args.put("quizSessionId", 99L);
        args.put("title", "红楼梦第一回");
        ObjectNode question = args.putArray("questions").addObject();
        question.put("prompt", "作者是谁？");
        question.put("type", "single");
        question.putArray("options").add("曹雪芹").add("高鹗");
        ObjectNode finish = decisionNode(true, "等用户作答", null, null);
        finish.put("reply", "题目在卡片上，作答后我来判分");
        when(modelClient.decideNextStepStream(any(), any(), any(), any()))
                .thenReturn(call(emit), call(finish));
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));

        AtomicReference<String> card = new AtomicReference<>();
        service.executeTurn(7L, 42L, "考考我", event -> {
            if ("quiz".equals(event.getEventType())) card.set(event.getDataContent());
        });

        verify(messageMapper).insertForActiveRun(
                argThatMessage("assistant", "quiz", null), eq(42L), eq(1L));
        // 同一份负载既落库也进流：前端两条路径渲染出的卡片必须一模一样。
        assertThat(card.get()).contains("\"id\":\"99\"").contains("作者是谁？").contains("曹雪芹");
    }

    @Test
    void acceptsMarkdownQuestionsWithoutForcingAnAdHocQuizCard() {
        stubTransactions();
        registry = new AgentToolRegistry(List.of(new EmitQuizTool()));
        service = new AgentConversationService(
                conversationMapper, messageMapper, modelClient, registry, properties,
                transactionTemplate, eventService, agentLeaseRenewer, billingService, objectMapper,
                planService, memoryService, new AgentQuizService(objectMapper));
        ObjectNode markdownQuestions = decisionNode(true, "1. sin 90° 等于多少？", null, null);
        ObjectNode emit = decisionNode(false, "", EmitQuizTool.NAME, null);
        ObjectNode args = ((ObjectNode) emit.path("action")).putObject("args");
        ObjectNode question = args.putArray("questions").addObject();
        question.put("prompt", "sin 90° 等于多少？");
        question.put("type", "single");
        question.putArray("options").add("0").add("1");
        ObjectNode finish = decisionNode(true, "请在答题卡中作答", null, null);
        when(modelClient.decideNextStepStream(any(), any(), any(), any()))
                .thenReturn(call(markdownQuestions), call(emit), call(finish));
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));

        service.executeTurn(7L, 42L, "给我出三道选择题", ignored -> { });

        verify(messageMapper, org.mockito.Mockito.never()).insertForActiveRun(
                argThatMessage("assistant", "quiz", null), eq(42L), eq(1L));
        verify(modelClient).decideNextStepStream(any(), any(), any(), any());
    }

    @Test
    void aCodeDeliverableIsPersistedAsAMessageSoTheUserCanStillOpenItLater() {
        // 代码是这一轮真正的产出：只发事件的话刷新一次源码就没了，必须同时落一条消息。
        stubTransactions();
        registry = new AgentToolRegistry(List.of(new EmitArtifactTool()));
        service = new AgentConversationService(
                conversationMapper, messageMapper, modelClient, registry, properties,
                transactionTemplate, eventService, agentLeaseRenewer, billingService, objectMapper,
                planService, memoryService, new AgentQuizService(objectMapper));
        ObjectNode emit = decisionNode(false, "把文件交出去", EmitArtifactTool.NAME, null);
        ObjectNode args = ((ObjectNode) emit.path("action")).putObject("args");
        args.put("name", "WebSocket 聊天示例");
        args.put("runHint", "npm install && node server.js");
        ArrayNode files = args.putArray("files");
        files.addObject().put("path", "server.js").put("content", "const ws = require('ws');");
        files.addObject().put("path", "public/index.html").put("content", "<!doctype html><title>chat</title>");
        ObjectNode finish = decisionNode(true, "", null, null);
        finish.put("reply", "已完成，按 npm install && node server.js 跑起来就能聊天");
        when(modelClient.decideNextStepStream(any(), any(), any(), any()))
                .thenReturn(call(emit), call(finish));
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));

        AtomicReference<String> card = new AtomicReference<>();
        service.executeTurn(7L, 42L, "帮我写一个 node.js + ws 的聊天示例", event -> {
            if ("artifact".equals(event.getEventType())) card.set(event.getDataContent());
        });

        verify(messageMapper).insertForActiveRun(
                argThatMessage("assistant", "artifact", null), eq(42L), eq(1L));
        // 同一份负载既落库也进流，并且入口自动落在能直接打开的 html 上。
        assertThat(card.get()).contains("server.js").contains("public/index.html")
                .contains("\"entry\":\"public/index.html\"");
        // 已经交付过了，收口时不必再被自检拦一轮。
        verify(modelClient, org.mockito.Mockito.times(2)).decideNextStepStream(any(), any(), any(), any());
        verify(messageMapper, org.mockito.Mockito.never()).insertForActiveRun(
                argThatMessage("assistant", "reflection", null), eq(42L), eq(1L));
    }

    @Test
    void claimingTheCodeIsDoneWithoutDeliveringItIsReturnedDirectly() {
        stubTransactions();
        registry = new AgentToolRegistry(List.of(new EmitArtifactTool()));
        service = new AgentConversationService(
                conversationMapper, messageMapper, modelClient, registry, properties,
                transactionTemplate, eventService, agentLeaseRenewer, billingService, objectMapper,
                planService, memoryService, new AgentQuizService(objectMapper));
        ObjectNode empty = decisionNode(true, "", null, null);
        empty.put("reply", "已完成 Node.js + ws 服务端及浏览器客户端聊天示例。");
        when(modelClient.decideNextStepStream(any(), any(), any(), any())).thenReturn(call(empty));
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));

        service.executeTurn(7L, 42L, "帮我写一个 node.js + ws 的聊天示例", ignored -> { });

        verify(modelClient).decideNextStepStream(any(), any(), any(), any());
        verify(messageMapper, org.mockito.Mockito.never()).insertForActiveRun(
                argThatMessage("assistant", "reflection", null), eq(42L), eq(1L));
        verify(messageMapper).insertForActiveRun(
                argThatMessage("assistant", "answer", null), eq(42L), eq(1L));
    }

    @Test
    void aReplyThatActuallyCarriesTheCodeIsNotSentBack() {
        // 代码写在围栏里用户就看得见、复制得走，这种回复不该被"没交付"的门拦下。
        stubTransactions();
        registry = new AgentToolRegistry(List.of(new EmitArtifactTool()));
        service = new AgentConversationService(
                conversationMapper, messageMapper, modelClient, registry, properties,
                transactionTemplate, eventService, agentLeaseRenewer, billingService, objectMapper,
                planService, memoryService, new AgentQuizService(objectMapper));
        ObjectNode inline = decisionNode(true, "", null, null);
        inline.put("reply", "已完成，代码如下：\n```js\nconsole.log('hi');\n```");
        when(modelClient.decideNextStepStream(any(), any(), any(), any())).thenReturn(call(inline));
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));

        service.executeTurn(7L, 42L, "写一个打印 hi 的脚本", ignored -> { });

        verify(modelClient, org.mockito.Mockito.times(1)).decideNextStepStream(any(), any(), any(), any());
        verify(messageMapper, org.mockito.Mockito.never()).insertForActiveRun(
                argThatMessage("assistant", "reflection", null), eq(42L), eq(1L));
    }

    @Test
    void aSubmittedCardIsStoredAsReadableTextAndDoesNotBecomeTheTitle() {
        stubTransactions();
        AgentConversation ready = conversation("ready");
        ready.setTitle("新对话");
        when(conversationMapper.selectById(7L)).thenReturn(ready);
        when(conversationMapper.update(any(), any())).thenReturn(1);
        AgentMessage emitted = new AgentMessage();
        emitted.setId(1L);
        emitted.setConversationId(7L);
        emitted.setRole("assistant");
        emitted.setKind("quiz");
        emitted.setContent("{\"type\":\"quiz\",\"id\":\"99\",\"title\":\"红楼梦第一回\",\"questions\":"
                + "[{\"id\":\"q1\",\"type\":\"single\",\"prompt\":\"作者是谁？\","
                + "\"options\":[\"曹雪芹\",\"高鹗\"]}]}");
        when(messageMapper.selectList(any())).thenReturn(List.of(emitted));

        service.prepareTurn(7L, 42L, "{\"type\":\"submit_quiz\",\"quiz_id\":\"99\",\"answers\":"
                + "[{\"question_id\":\"q1\",\"answer\":\"曹雪芹\"}]}");

        verify(messageMapper).insert(org.mockito.ArgumentMatchers.argThat(message ->
                "user".equals(message.getRole())
                        && "quiz_answer".equals(message.getKind())
                        && message.getContent().contains("作者是谁？")
                        && message.getContent().contains("作答：曹雪芹")
                        && !message.getContent().contains("submit_quiz")));
        // 一次作答不是新诉求：标题不该变成"测验会话 id=…"。
        org.mockito.Mockito.verify(conversationMapper, org.mockito.Mockito.never()).updateById(any());
    }

    @Test
    void failedConversationCanBeRetried() {
        stubTransactions();
        ObjectNode decision = decisionNode(true, "重试成功", null, null);
        decision.put("reply", "好的，重试完成了");
        when(modelClient.decideNextStepStream(any(), any(), any(), any())).thenReturn(call(decision));
        AgentConversation failed = conversation("running");
        when(conversationMapper.selectById(7L)).thenReturn(failed);
        when(conversationMapper.update(any(), any())).thenReturn(1);

        AgentConversation result = service.executeTurn(7L, 42L, "重试", ignored -> { });

        assertThat(result.getStatus()).isEqualTo("ready");
        verify(modelClient).decideNextStepStream(any(), any(), any(), any());
    }

    @Test
    void cancelRequestStopsLoopBeforeModelCall() {
        stubTransactions();
        AgentConversation running = conversation("running");
        running.setCancelRequested(1);
        when(conversationMapper.selectById(7L)).thenReturn(running);
        when(conversationMapper.update(any(), any())).thenReturn(1);

        AgentConversation result = service.executeClaimedTurn(7L, 42L, 1L, ignored -> { });

        assertThat(result.getStatus()).isEqualTo("ready");
        org.mockito.Mockito.verifyNoInteractions(modelClient);
        verify(messageMapper).insertForActiveRun(
                argThatMessage("assistant", "answer", null), eq(42L), eq(1L));
    }

    @Test
    void modelCancellationPersistsStoppedAnswerAndCompletesWithoutFailure() {
        stubTransactions();
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));
        when(modelClient.decideNextStepStream(any(), any(), any(), any())).thenAnswer(invocation -> {
            java.util.function.Consumer<String> deltas = invocation.getArgument(2);
            deltas.accept("partial reply");
            throw new CancellationException("cancelled");
        });
        List<String> eventTypes = new java.util.ArrayList<>();

        AgentConversation result = service.executeClaimedTurn(
                7L, 42L, 1L, event -> eventTypes.add(event.getEventType()));

        assertThat(result.getStatus()).isEqualTo("ready");
        assertThat(eventTypes).containsExactly("answer_delta", "answer", "complete");
        verify(messageMapper).insertForActiveRun(
                org.mockito.ArgumentMatchers.argThat(message ->
                        "assistant".equals(message.getRole())
                                && "answer".equals(message.getKind())
                                && message.getContent().contains("已停止")),
                eq(42L), eq(1L));
        verify(eventMapper, org.mockito.Mockito.never()).insertForRun(
                org.mockito.ArgumentMatchers.argThat(event -> "error".equals(event.getEventType())),
                eq(42L), eq(false));
    }

    @Test
    void modelFailurePersistsAndDeliversDurableErrorEvent() {
        stubTransactions();
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));
        when(modelClient.decideNextStepStream(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("model down"));
        AtomicReference<AgentEvent> delivered = new AtomicReference<>();

        service.prepareTurn(7L, 42L, "失败测试");
        assertThatThrownBy(() -> service.executeClaimedTurn(7L, 42L, 1L, delivered::set))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model down");

        assertThat(delivered.get()).isNotNull();
        assertThat(delivered.get().getId()).isPositive();
        assertThat(delivered.get().getEventType()).isEqualTo("error");
        assertThat(delivered.get().getDataContent()).contains("model down");
    }

    /**
     * 回归：计费失败不能把一轮已经答完的对话变成"被新一轮取代"。
     *
     * <p>{@code finalizeReady} 先 {@code complete()} 把状态推到 ready、再计费。计费抛异常时，
     * 异常会一路冒到 {@link AgentTurnCoordinator}，那里再调 {@code fail()}——但 fail 的
     * {@code WHERE status='running'} 此时命中 0 行，于是抛 {@code AgentRunSupersededException}，
     * 被当成并发取代静默吞掉：终态事件不投递、日志写着 superseded，真正的账务故障一点痕迹都没有。</p>
     *
     * <p>这里把 {@code conversationMapper.update} 换成一个按状态走的真状态机，让
     * {@code fail()} 像生产一样落空——否则单测里 update 永远返回 1，fail 会"成功"，
     * 就把这条真实链路遮掉了。</p>
     */
    @Test
    void aBillingFailureDoesNotSwallowTheTerminalEvent() {
        stubTransactions();
        ObjectNode decision = decisionNode(true, "", null, null);
        decision.put("reply", "已经答完了");
        when(modelClient.decideNextStepStream(any(), any(), any(), any()))
                .thenReturn(call(decision));
        AtomicReference<String> status = new AtomicReference<>("ready");
        when(conversationMapper.update(any(), any())).thenAnswer(invocation -> {
            String current = status.get();
            if ("ready".equals(current)) {
                status.set("running");
                return 1;
            }
            if ("running".equals(current)) {
                status.set("ready");
                return 1;
            }
            return 0;
        });
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));
        doThrow(new IllegalStateException("积分账户不存在"))
                .when(billingService)
                .chargeTokens(any(), any(), any(), any(), any(), any(), any());
        List<String> eventTypes = new ArrayList<>();

        AgentConversation result = service.executeTurn(7L, 42L, "你好",
                event -> eventTypes.add(event.getEventType()));

        // 账务失败归账务，用户该拿到的东西一样不少。
        assertThat(eventTypes).containsSubsequence("answer", "complete");
        assertThat(result.getStatus()).isEqualTo("ready");
        verify(eventMapper, org.mockito.Mockito.never()).insertForRun(
                org.mockito.ArgumentMatchers.argThat(event -> "error".equals(event.getEventType())),
                eq(42L), eq(false));
    }

    @Test
    void answerDeltaTailIsFlushedBeforeFinalAnswer() {
        stubTransactions();
        ObjectNode decision = decisionNode(true, "", null, null);
        decision.put("reply", "短回复");
        when(modelClient.decideNextStepStream(any(), any(), any(), any())).thenAnswer(invocation -> {
            java.util.function.Consumer<String> deltas = invocation.getArgument(2);
            deltas.accept("短");
            deltas.accept("回复");
            return call(decision);
        });
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));
        List<String> eventTypes = new java.util.ArrayList<>();

        service.executeTurn(7L, 42L, "你好", event -> eventTypes.add(event.getEventType()));

        assertThat(eventTypes).containsSubsequence("answer_delta", "answer", "complete");
    }

    @Test
    void everyTurnSeesTheWholeToolTableAndTheSameDecisionTail() {
        // 关键词门禁移除后，"这轮算不算普通对话"由模型自己判断，服务端不再替它裁剪能力。
        stubTransactions();
        ObjectNode decision = decisionNode(true, "", null, null);
        decision.put("reply", "这是普通对话回复");
        when(modelClient.decideNextStepStream(any(), any(), any(), any())).thenReturn(call(decision));
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                message("user", "text", "上一轮我问了什么"),
                message("assistant", "text", "上一轮我答了什么"),
                message("user", "text", "随便聊聊")));

        service.executeTurn(7L, 42L, "随便聊聊", ignored -> { });

        @SuppressWarnings("unchecked")
        var turns = (org.mockito.ArgumentCaptor<List<AgentInputTurn>>)
                (org.mockito.ArgumentCaptor<?>) org.mockito.ArgumentCaptor.forClass(List.class);
        var toolDescriptions = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(modelClient).decideNextStepStream(
                turns.capture(), toolDescriptions.capture(), any(), any());
        assertThat(toolDescriptions.getValue()).contains("blog_generate");
        List<AgentInputTurn> sent = turns.getValue();
        // 对话记录不再靠"以下是……对话记录"这行标题来声明，role 本身就是标题：
        // 用户说的话进 user 轮、智能体自己说过的话进 assistant 轮，
        // 模型因此不用靠猜标题就知道哪句是它自己上一轮说的。
        assertThat(sent).anySatisfy(turn -> {
            assertThat(turn.isUser()).isTrue();
            assertThat(turn.text()).contains("上一轮我问了什么");
        });
        assertThat(sent).anySatisfy(turn -> {
            assertThat(turn.isUser()).isFalse();
            assertThat(turn.text()).contains("上一轮我答了什么");
        });
        // 会话状态收在最后一轮 user，模型要回答的正是这一轮；严格交替则是为了兼容
        // 那些拒收连续同 role 的 OpenAI 兼容端点。
        assertThat(sent).hasSizeGreaterThan(1);
        assertThat(sent.get(0).isUser()).isTrue();
        assertThat(sent.get(sent.size() - 1).isUser()).isTrue();
        for (int index = 1; index < sent.size(); index++) {
            assertThat(sent.get(index).role()).isNotEqualTo(sent.get(index - 1).role());
        }
        assertThat(sent.get(sent.size() - 1).text())
                .endsWith("请根据以上信息决定下一步：需要动手就返回 action，已经真正做完就返回 done=true 的最终回复。");
    }

    @Test
    void aDoneReplyIsDeliveredWithoutAPlanReflection() {
        stubTransactions();
        when(planService.load(any())).thenReturn(List.of(
                new AgentPlanItem("查一下知识库", "IN_PROGRESS", "")));
        when(planService.render(any())).thenReturn("1. 查一下知识库 (IN_PROGRESS)");
        ObjectNode premature = decisionNode(true, "", null, null);
        premature.put("reply", "都办好了");
        when(modelClient.decideNextStepStream(any(), any(), any(), any())).thenReturn(call(premature));
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));
        List<String> eventTypes = new java.util.ArrayList<>();

        AgentConversation result = service.executeTurn(
                7L, 42L, "帮我复习", true, event -> eventTypes.add(event.getEventType()));

        assertThat(result.getStatus()).isEqualTo("ready");
        assertThat(eventTypes).contains("answer").doesNotContain("reflection");
        verify(modelClient).decideNextStepStream(any(), any(), any(), any());
    }

    @Test
    void unfinishedPlansDoNotCauseExtraModelRounds() {
        stubTransactions();
        when(planService.load(any())).thenReturn(List.of(
                new AgentPlanItem("永远没做完的步骤", "PENDING", "")));
        ObjectNode premature = decisionNode(true, "", null, null);
        premature.put("reply", "都办好了");
        when(modelClient.decideNextStepStream(any(), any(), any(), any())).thenReturn(call(premature));
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));

        AgentConversation result = service.executeTurn(7L, 42L, "帮我复习", true, ignored -> { });

        assertThat(result.getStatus()).isEqualTo("ready");
        verify(modelClient).decideNextStepStream(any(), any(), any(), any());
        verify(messageMapper).insertForActiveRun(
                argThatMessage("assistant", "answer", null), eq(42L), eq(1L));
    }

    @Test
    void fastModeSkipsThePlanSelfCheckSoTheAnswerArrivesInOneRound() {
        // 用户没开深度思考：计划里还有未收口的步骤也直接交付，不再"先回复再撤回"。
        stubTransactions();
        when(planService.load(any())).thenReturn(List.of(
                new AgentPlanItem("查一下知识库", "IN_PROGRESS", "")));
        ObjectNode premature = decisionNode(true, "", null, null);
        premature.put("reply", "都办好了");
        when(modelClient.decideNextStepStream(any(), any(), any(), any())).thenReturn(call(premature));
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));
        List<String> eventTypes = new java.util.ArrayList<>();

        AgentConversation result = service.executeTurn(
                7L, 42L, "帮我复习", event -> eventTypes.add(event.getEventType()));

        assertThat(result.getStatus()).isEqualTo("ready");
        assertThat(eventTypes).contains("answer").doesNotContain("reflection");
        verify(modelClient).decideNextStepStream(any(), any(), any(), any());
    }

    @Test
    void aToolFailureDoesNotCauseAnAutomaticCritiqueRound() {
        stubTransactions();
        ObjectNode failing = decisionNode(false, "调用工具", "failing_tool", null);
        ObjectNode hides = decisionNode(true, "", null, null);
        hides.put("reply", "已经帮你生成好了");
        when(modelClient.decideNextStepStream(any(), any(), any(), any()))
                .thenReturn(call(failing), call(hides));
        service = new AgentConversationService(
                conversationMapper, messageMapper, modelClient,
                new AgentToolRegistry(List.of(new FailingTool("failing_tool"))), properties,
                transactionTemplate, eventService, agentLeaseRenewer, billingService, objectMapper,
                planService, memoryService, new AgentQuizService(objectMapper));
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));
        List<String> eventTypes = new java.util.ArrayList<>();

        service.executeTurn(7L, 42L, "帮我生成", false,
                event -> eventTypes.add(event.getEventType()));

        assertThat(eventTypes).containsSubsequence("tool_error", "answer").doesNotContain("reflection");
    }

    @Test
    void deepThinkingDoesNotRepeatTheSameDoneDecision() {
        stubTransactions();
        when(planService.load(any())).thenReturn(List.of(
                new AgentPlanItem("永远没做完的步骤", "PENDING", "")));
        ObjectNode premature = decisionNode(true, "", null, null);
        premature.put("reply", "都办好了");
        when(modelClient.decideNextStepStream(any(), any(), any(), any())).thenReturn(call(premature));
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));
        List<String> eventTypes = new java.util.ArrayList<>();

        service.executeTurn(7L, 42L, "帮我复习", true, event -> eventTypes.add(event.getEventType()));

        assertThat(eventTypes).contains("answer").doesNotContain("reflection");
        verify(modelClient).decideNextStepStream(any(), any(), any(), any());
    }

    @Test
    void deepThinkingStreamsTheFirstDoneDraftWithoutHoldingItBack() {
        stubTransactions();
        when(planService.load(any())).thenReturn(List.of(
                new AgentPlanItem("查一下知识库", "IN_PROGRESS", "")));
        ObjectNode premature = decisionNode(true, "", null, null);
        premature.put("reply", "都办好了");
        AtomicInteger calls = new AtomicInteger();
        when(modelClient.decideNextStepStream(any(), any(), any(), any())).thenAnswer(invocation -> {
            java.util.function.Consumer<String> deltas = invocation.getArgument(2);
            if (calls.getAndIncrement() == 0) {
                deltas.accept("都办好了");
                return call(premature);
            }
            throw new AssertionError("done=true must end the turn without a self-check retry");
        });
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));
        List<String> eventTypes = new java.util.ArrayList<>();

        service.executeTurn(7L, 42L, "帮我复习", true, event -> eventTypes.add(event.getEventType()));

        assertThat(eventTypes).containsSubsequence("answer_delta", "answer").doesNotContain("reflection");
    }

    @Test
    void preservesDecodedUpstreamAnswerDeltaBoundaries() {
        stubTransactions();
        ObjectNode decision = decisionNode(true, "", null, null);
        decision.put("reply", "第一段第二段");
        when(modelClient.decideNextStepStream(any(), any(), any(), any())).thenAnswer(invocation -> {
            java.util.function.Consumer<String> deltas = invocation.getArgument(2);
            deltas.accept("第一段");
            deltas.accept("第二段");
            return call(decision);
        });
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));
        List<String> deltas = new java.util.ArrayList<>();

        service.executeTurn(7L, 42L, "你好", event -> {
            if ("answer_delta".equals(event.getEventType())) deltas.add(event.getDataContent());
        });

        assertThat(deltas).containsExactly("第一段", "第二段");
    }

    @Test
    void batchesTinyAnswerDeltasAfterDeliveringTheFirstOneImmediately() {
        stubTransactions();
        ObjectNode decision = decisionNode(true, "", null, null);
        decision.put("reply", "第一二三四五六七八九十十一十二十三十四");
        when(modelClient.decideNextStepStream(any(), any(), any(), any())).thenAnswer(invocation -> {
            java.util.function.Consumer<String> deltas = invocation.getArgument(2);
            deltas.accept("第一");
            deltas.accept("二三");
            deltas.accept("四五");
            deltas.accept("六七八九十十一十二十三十四");
            return call(decision);
        });
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));
        List<String> deltas = new java.util.ArrayList<>();

        service.executeTurn(7L, 42L, "你好", event -> {
            if ("answer_delta".equals(event.getEventType())) deltas.add(event.getDataContent());
        });

        assertThat(deltas).containsExactly("第一", "二三四五六七八九十十一十二十三十四");
    }

    /**
     * 回归：合并时间窗内的连续增量只能产生一条 answer_delta 事件。
     *
     * <p>这条断言守的是"响应慢"的根因——早先每 24 字符就落库并推送一次，
     * 一次回答里会插入上百次数据库往返。上游在一瞬间吐出 40 个分片时，
     * 现在必须合并成「首片 + 至多一次窗口 flush」，而不是 40 条事件。</p>
     */
    @Test
    void coalescesBurstOfAnswerDeltasIntoOneEventPerWindow() {
        stubTransactions();
        ObjectNode decision = decisionNode(true, "", null, null);
        decision.put("reply", "长回答".repeat(20));
        when(modelClient.decideNextStepStream(any(), any(), any(), any())).thenAnswer(invocation -> {
            java.util.function.Consumer<String> deltas = invocation.getArgument(2);
            deltas.accept("首");
            for (int index = 0; index < 40; index++) {
                deltas.accept("片" + index);
            }
            return call(decision);
        });
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));
        List<String> deltas = new java.util.ArrayList<>();

        service.executeTurn(7L, 42L, "你好", event -> {
            if ("answer_delta".equals(event.getEventType())) deltas.add(event.getDataContent());
        });

        // 41 个上游分片压缩成 2 条事件（首片 + 一次窗口合并），文本不丢不重。
        assertThat(deltas).hasSizeLessThanOrEqualTo(2);
        assertThat(String.join("", deltas)).isEqualTo("首" + java.util.stream.IntStream.range(0, 40)
                .mapToObj(index -> "片" + index).collect(java.util.stream.Collectors.joining()));
    }

    @Test
    void escapedBackticksInReplyAreNormalizedForMarkdownRendering() {
        stubTransactions();
        ObjectNode decision = decisionNode(true, "", null, null);
        decision.put("reply", "使用 \\`Axios + onDownloadProgress\\` 接收，不用 \\`EventSource\\`");
        when(modelClient.decideNextStepStream(any(), any(), any(), any())).thenAnswer(invocation -> {
            java.util.function.Consumer<String> deltas = invocation.getArgument(2);
            deltas.accept("使用 \\`Axios + onDownload");
            deltas.accept("Progress\\` 接收");
            return call(decision);
        });
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));
        List<String> deltas = new java.util.ArrayList<>();

        service.executeTurn(7L, 42L, "你好", event -> {
            if ("answer_delta".equals(event.getEventType())) deltas.add(event.getDataContent());
        });

        assertThat(deltas).allSatisfy(delta -> assertThat(delta).doesNotContain("\\`"));
        verify(messageMapper).insertForActiveRun(
                org.mockito.ArgumentMatchers.argThat(message ->
                        "assistant".equals(message.getRole())
                                && "answer".equals(message.getKind())
                                && message.getContent().contains("`Axios + onDownloadProgress`")
                                && !message.getContent().contains("\\`")),
                eq(42L), eq(1L));
    }

    @Test
    void normalizeAnswerUnescapesOnlyBacktickSequences() {
        assertThat(AgentConversationService.normalizeAnswer("使用 \\`code\\` 说明"))
                .isEqualTo("使用 `code` 说明");
        assertThat(AgentConversationService.normalizeAnswer("路径 C:\\Users\\foo"))
                .isEqualTo("路径 C:\\Users\\foo");
        assertThat(AgentConversationService.normalizeAnswer(null)).isNull();
        assertThat(AgentConversationService.normalizeAnswer("没有反引号")).isEqualTo("没有反引号");
    }

    @Test
    void oldWorkerCannotPublishAfterANewerRunWasClaimed() {
        ObjectNode decision = decisionNode(true, "不应公开的旧轮思考", null, null);
        decision.put("reply", "旧轮迟到回复");
        AgentConversation oldRun = conversation("running");
        AgentConversation newRun = conversation("running");
        newRun.setRunVersion(2L);
        when(conversationMapper.selectById(7L)).thenReturn(oldRun, newRun, newRun);

        AgentConversation result = service.executeClaimedTurn(7L, 42L, 1L, ignored -> { });

        assertThat(result.getRunVersion()).isEqualTo(2L);
        assertThat(result.getStatus()).isEqualTo("running");
        org.mockito.Mockito.verify(messageMapper, org.mockito.Mockito.never())
                .insertForActiveRun(any(), any(), any(Long.class));
    }

    @Test
    void claimedWorkerSchedulesAndCancelsIndependentLeaseRenewal() {
        AgentConversation running = conversation("running");
        running.setCancelRequested(1);
        when(conversationMapper.selectById(7L)).thenReturn(running);
        when(conversationMapper.update(any(), any())).thenReturn(1);
        stubTransactions();

        service.executeClaimedTurn(7L, 42L, 1L, ignored -> { });

        verify(agentLeaseRenewer).scheduleAtFixedRate(
                any(Runnable.class), eq(60L), eq(60L), eq(java.util.concurrent.TimeUnit.SECONDS));
        verify(leaseRenewal).cancel(false);
    }

    @Test
    void leaseRenewalFencesTheClaimedRunAndStopsWhenSuperseded() {
        AgentConversation running = conversation("running");
        running.setCancelRequested(1);
        when(conversationMapper.selectById(7L)).thenReturn(running);
        when(conversationMapper.update(any(), any())).thenReturn(1);
        stubTransactions();
        AtomicReference<Runnable> renewal = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            renewal.set(invocation.getArgument(0));
            return leaseRenewal;
        }).when(agentLeaseRenewer).scheduleAtFixedRate(
                any(Runnable.class), any(Long.class), any(Long.class), any());

        service.executeClaimedTurn(7L, 42L, 1L, ignored -> { });
        when(conversationMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> renewal.get().run())
                .isInstanceOf(AgentRunSupersededException.class);
    }

    @Test
    void leaseSchedulerRejectionFailsTheClaimedRunBeforeRemoteWork() {
        stubTransactions();
        when(conversationMapper.update(any(), any())).thenReturn(1);
        org.mockito.Mockito.doThrow(new java.util.concurrent.RejectedExecutionException("shutdown"))
                .when(agentLeaseRenewer).scheduleAtFixedRate(
                        any(Runnable.class), any(Long.class), any(Long.class), any());
        AtomicReference<AgentEvent> delivered = new AtomicReference<>();

        assertThatThrownBy(() -> service.executeClaimedTurn(7L, 42L, 1L, delivered::set))
                .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);

        org.mockito.Mockito.verifyNoInteractions(modelClient);
        assertThat(delivered.get()).isNotNull();
        assertThat(delivered.get().getEventType()).isEqualTo("error");
        assertThat(delivered.get().getId()).isPositive();
        verify(eventMapper).insertForRun(
                org.mockito.ArgumentMatchers.argThat(event -> "error".equals(event.getEventType())),
                eq(42L), eq(false));
    }

    @Test
    void namingAToolThatDoesNotExistTripsTheFailureCapInsteadOfBurningEveryStep() {
        // 曾经这条分支只是静默 continue：模型可以一直点名不存在的工具，
        // 把 maxSteps 全烧完，用户等半天只收到一句"步数已达上限"。
        stubTransactions();
        ObjectNode bogus = decisionNode(false, "我要用这个工具", "并不存在的工具", null);
        when(modelClient.decideNextStepStream(any(), any(), any(), any())).thenReturn(call(bogus));
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));

        AgentConversation result = service.executeTurn(7L, 42L, "随便做点什么", ignored -> { });

        assertThat(result.getStatus()).isEqualTo("ready");
        // maxToolFailures=2：两次就收工，而不是打满 maxSteps=10。
        verify(modelClient, org.mockito.Mockito.times(2))
                .decideNextStepStream(any(), any(), any(), any());
        verify(messageMapper).insertForActiveRun(
                org.mockito.ArgumentMatchers.argThat(message ->
                        "assistant".equals(message.getRole())
                                && "answer".equals(message.getKind())
                                && message.getContent().contains("并不存在的工具")),
                eq(42L), eq(1L));
    }

    @Test
    void attachmentsOutsideTheContextWindowAreNotResentOnEveryLaterTurn() {
        // 附件和文本上下文用同一个窗口：早就翻篇的图片不该在之后每一轮的每一步被重新上传计费。
        stubTransactions();
        org.mockito.Mockito.when(properties.getContextMessageLimit()).thenReturn(5);
        List<AgentMessage> history = new java.util.ArrayList<>();
        history.add(userMessage(1L, "看看这张图", "[{\"url\":\"https://cdn.example.com/old.png\","
                + "\"name\":\"old.png\",\"contentType\":\"image/png\",\"size\":12}]"));
        for (long id = 2L; id <= 7L; id++) {
            history.add(userMessage(id, "第 " + id + " 条", null));
        }
        when(messageMapper.selectList(any())).thenReturn(history);
        ObjectNode decision = decisionNode(true, "", null, null);
        decision.put("reply", "好的");
        when(modelClient.decideNextStepStream(any(), any(), any(), any())).thenReturn(call(decision));
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));

        service.executeTurn(7L, 42L, "继续", ignored -> { });

        verify(modelClient).decideNextStepStream(any(), any(), any(), any());
        verify(modelClient, org.mockito.Mockito.never())
                .decideNextStepStream(any(), any(), org.mockito.ArgumentMatchers.anyList(), any(), any());
    }

    @Test
    void anAttachmentInsideTheContextWindowIsStillSentSoFollowUpQuestionsWork() {
        stubTransactions();
        org.mockito.Mockito.when(properties.getContextMessageLimit()).thenReturn(5);
        when(messageMapper.selectList(any())).thenReturn(List.of(
                userMessage(1L, "旧消息", null),
                userMessage(2L, "看看这张图", "[{\"url\":\"https://cdn.example.com/new.png\","
                        + "\"name\":\"new.png\",\"contentType\":\"image/png\",\"size\":12}]"),
                userMessage(3L, "这图里是什么", null)));
        ObjectNode decision = decisionNode(true, "", null, null);
        decision.put("reply", "这是一张示意图");
        when(modelClient.decideNextStepStream(any(), any(), org.mockito.ArgumentMatchers.anyList(),
                any(), any())).thenReturn(call(decision));
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));

        service.executeTurn(7L, 42L, "这图里是什么", ignored -> { });

        var attachments = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(modelClient).decideNextStepStream(any(), any(), attachments.capture(), any(), any());
        assertThat(attachments.getValue()).hasSize(1);
    }

    /**
     * 压缩接在真实的推理循环里：装配上下文之前先换掉头部，检查点落库成为新的地板。
     *
     * <p>回归：压缩服务写好了却没接进 runLoop 就是一段死代码，而且很难被发现——
     * 条数窗口会先把旧消息静默丢掉，token 压力永远到不了阈值，
     * 于是"有窗口没压缩"看起来和"压缩没触发"完全一样。</p>
     */
    @Test
    void compactionRunsInsideTheLoopAndShadowsTheEarlyTranscript() {
        stubTransactions();
        org.mockito.Mockito.when(properties.isCompactionEnabled()).thenReturn(true);
        org.mockito.Mockito.when(properties.getContextMessageLimit()).thenReturn(10);
        org.mockito.Mockito.when(properties.getContextWindowTokens()).thenReturn(128000);
        org.mockito.Mockito.when(properties.getCompactionThresholdRatio()).thenReturn(0.8D);
        org.mockito.Mockito.when(properties.getCompactionRetainRatio()).thenReturn(0.16D);
        List<AgentMessage> history = new ArrayList<>();
        for (int index = 1; index <= 12; index++) {
            history.add(userMessage((long) index, "第" + index + "条：" + "记录".repeat(200), null));
        }
        when(messageMapper.selectList(any())).thenReturn(history);
        ObjectNode summary = decisionNode(true, "", null, null);
        summary.put("reply", "检查点：用户要整理笔记");
        ObjectNode done = decisionNode(true, "", null, null);
        done.put("reply", "已整理好");
        when(modelClient.decideNextStepStream(any(), any(), any(), any()))
                .thenReturn(call(summary, new ModelUsage(1200, 800, 60)), call(done));
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));

        service.executeTurn(7L, 42L, "继续整理", ignored -> { });

        verify(messageMapper).insertForActiveRun(
                argThatMessage("user", "compaction", null), eq(42L), eq(1L));
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<AgentInputTurn>> turns =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(modelClient, org.mockito.Mockito.times(2))
                .decideNextStepStream(turns.capture(), any(), any(), any());
        String mainRequest = turns.getAllValues().get(1).stream()
                .map(AgentInputTurn::text).collect(java.util.stream.Collectors.joining("\n"));
        assertThat(mainRequest).contains("检查点：用户要整理笔记").contains("第8条")
                .doesNotContain("第1条：").doesNotContain("第7条：");
        // 摘要是一次真实的模型调用，不报账就等于让压缩绕过计费。
        org.mockito.ArgumentCaptor<ModelUsage> billed = org.mockito.ArgumentCaptor.forClass(ModelUsage.class);
        verify(billingService).chargeTokens(eq(42L), any(), any(), any(), billed.capture(), any(), any());
        assertThat(billed.getValue()).isEqualTo(new ModelUsage(1200, 800, 60));
    }

    private AgentMessage userMessage(Long id, String content, String attachmentsJson) {
        AgentMessage message = new AgentMessage();
        message.setId(id);
        message.setConversationId(7L);
        message.setRole("user");
        message.setKind("message");
        message.setContent(content);
        message.setAttachmentsJson(attachmentsJson);
        message.setCreatedAt(LocalDateTime.now().minusMinutes(10 - id));
        return message;
    }

    private AgentMessage argThatMessage(String role, String kind, String toolName) {
        return org.mockito.ArgumentMatchers.argThat(message ->
                role.equals(message.getRole())
                        && kind.equals(message.getKind())
                        && java.util.Objects.equals(toolName, message.getToolName()));
    }

    /** 这一轮真正落库的消息，按写入顺序。 */
    private List<AgentMessage> capturedMessages() {
        org.mockito.ArgumentCaptor<AgentMessage> captured =
                org.mockito.ArgumentCaptor.forClass(AgentMessage.class);
        verify(messageMapper, org.mockito.Mockito.atLeastOnce())
                .insertForActiveRun(captured.capture(), eq(42L), eq(1L));
        return captured.getAllValues();
    }

    private List<String> invocationIdsOf(List<AgentMessage> messages, String kind, String toolName) {
        return messages.stream()
                .filter(message -> kind.equals(message.getKind()) && toolName.equals(message.getToolName()))
                .map(AgentMessage::getInvocationId)
                .toList();
    }

    private void stubTransactions() {
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any());
    }

    private AgentConversation conversation(String status) {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(7L);
        conversation.setUserId(42L);
        conversation.setStatus(status);
        conversation.setRunVersion(1L);
        conversation.setUpdatedAt(LocalDateTime.now());
        return conversation;
    }

    private AgentMessage message(String role, String kind, String content) {
        AgentMessage message = new AgentMessage();
        message.setId(1L);
        message.setConversationId(7L);
        message.setRole(role);
        message.setKind(kind);
        message.setContent(content);
        return message;
    }

    /** 一步多个调用：action 置空，改由 actions 数组承载。 */
    private ObjectNode batchDecision(String thought, String... toolNames) {
        ObjectNode decision = JsonNodeFactory.instance.objectNode();
        decision.put("done", false);
        decision.put("thought", thought);
        decision.putNull("action");
        var actions = decision.putArray("actions");
        for (String toolName : toolNames) {
            ObjectNode action = actions.addObject();
            action.put("tool", toolName);
            action.putObject("args");
        }
        return decision;
    }

    /**
     * 同一个工具、每个调用带一个不同的 topic。
     *
     * <p>同名工具是否被允许，取决于参数是否相同——所以验证"允许"时参数必须真的不同，
     * 而验证"拒绝"时把 topic 传成同一个值即可。用同一个构造器产出两种输入，
     * 免得两个用例的差异藏在手写的 JSON 里。</p>
     */
    private ObjectNode batchDecisionWithTopics(String thought, String tool, String... topics) {
        ObjectNode decision = JsonNodeFactory.instance.objectNode();
        decision.put("done", false);
        decision.put("thought", thought);
        decision.putNull("action");
        var actions = decision.putArray("actions");
        for (String topic : topics) {
            ObjectNode action = actions.addObject();
            action.put("tool", tool);
            action.putObject("args").put("topic", topic);
        }
        return decision;
    }

    /** 换一张工具表重建服务，用来验证"哪几个工具在表里"对编排行为的影响。 */
    private AgentConversationService serviceWith(AgentTool... tools) {
        return new AgentConversationService(
                conversationMapper, messageMapper, modelClient, new AgentToolRegistry(List.of(tools)),
                properties, transactionTemplate, eventService, agentLeaseRenewer, billingService,
                objectMapper, planService, memoryService, new AgentQuizService(objectMapper));
    }

    /**
     * 用**完整**构造器装一条带审批服务的执行链。
     *
     * <p>审批挂起只在"执行管道拿得到审批服务"时才可能发生，而 {@link #serviceWith} 装的简化链
     * 用的是 {@code AgentToolExecutionPipeline.legacy(...)}（审批服务为 null，遇到需要审批的调用
     * 只会抛"未配置工具审批服务"）。所以验证审批的用例必须自己把这条链接起来——
     * 这也正是审批路由坏掉却没人发现的原因：简化链根本走不到那段代码。</p>
     */
    private AgentConversationService serviceWithApproval(AgentTool... tools) {
        AgentToolRegistry approvalRegistry = new AgentToolRegistry(List.of(tools));
        AgentExecutionJournal journal = AgentExecutionJournal.disabled();
        AgentApprovalService approvalService = mock(AgentApprovalService.class);
        org.mockito.Mockito.lenient()
                .when(approvalService.request(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    // 四个字段都不能为 null：AgentRunLifecycle.waitForApproval 用 Map.of 拼载荷，
                    // 而 Map.of 遇到 null 值直接 NPE。
                    AgentApproval approval = new AgentApproval();
                    approval.setId(11L);
                    approval.setInvocationId("inv-approved");
                    approval.setToolName(invocation.getArgument(1, AgentToolInvocation.class).getToolName());
                    approval.setSummaryJson("{\"summary\":\"会对外发布内容\"}");
                    return approval;
                });
        AgentRunLifecycle lifecycle = new AgentRunLifecycle(conversationMapper, eventService, journal,
                properties, transactionTemplate, agentLeaseRenewer);
        AgentEventProjector projector = new AgentEventProjector(conversationMapper, messageMapper,
                eventService, transactionTemplate);
        AgentQuizService quizService = new AgentQuizService(objectMapper);
        AgentTurnCoordinator coordinator = new AgentTurnCoordinator(conversationMapper, eventService,
                lifecycle, journal, AgentStepExecutor.legacy(modelClient),
                new AgentToolExecutionPipeline(approvalRegistry, journal, approvalService));
        return new AgentConversationService(
                conversationMapper, messageMapper, modelClient, approvalRegistry, properties,
                transactionTemplate, eventService, billingService, objectMapper, planService,
                memoryService, quizService, journal,
                AgentRequestAssembler.legacy(approvalRegistry, properties, planService, quizService, objectMapper),
                lifecycle, projector, coordinator, AgentSkillCatalog.empty(),
                new AgentCompactionService(modelClient, properties, projector));
    }

    private ObjectNode decisionNode(boolean done, String thought, String tool, String input) {
        ObjectNode decision = JsonNodeFactory.instance.objectNode();
        decision.put("done", done);
        decision.put("thought", thought);
        if (!done) {
            ObjectNode action = decision.putObject("action");
            action.put("tool", tool);
            if (input != null) {
                action.putObject("args").put("input", input);
            }
        }
        return decision;
    }

    private ModelCallResult call(ObjectNode decision) {
        return ModelCallResult.of(decision, ModelUsage.NONE);
    }

    private ModelCallResult call(ObjectNode decision, ModelUsage usage) {
        return ModelCallResult.of(decision, usage, "agent-test-model");
    }

    /** A tool that succeeds and reports one progress event (name: blog_generate). */
    static class StubTool implements AgentTool {
        @Override
        public String name() { return "blog_generate"; }

        @Override
        public String description() { return "生成博客"; }

        @Override
        public com.fasterxml.jackson.databind.JsonNode parametersSchema() {
            return JsonNodeFactory.instance.objectNode();
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode execute(Long userId,
                                                               com.fasterxml.jackson.databind.JsonNode args,
                                                               ProgressListener onProgress) {
            onProgress.onProgress("开始生成");
            onProgress.onToolDelta("draft-tail");
            return JsonNodeFactory.instance.objectNode().put("ok", true);
        }
    }

    /** 只读桩工具：记下自己被跑过几次，供并发批次与单步上限的用例断言。 */
    static class ReadOnlyStubTool implements AgentTool {
        private final String nameValue;
        final AtomicInteger executions = new AtomicInteger();

        ReadOnlyStubTool(String nameValue) {
            this.nameValue = nameValue;
        }

        @Override
        public String name() { return nameValue; }

        @Override
        public String description() { return "只读查询 " + nameValue; }

        @Override
        public boolean readOnly() { return true; }

        @Override
        public com.fasterxml.jackson.databind.JsonNode parametersSchema() {
            return JsonNodeFactory.instance.objectNode();
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode execute(Long userId,
                                                               com.fasterxml.jackson.databind.JsonNode args,
                                                               ProgressListener onProgress) {
            executions.incrementAndGet();
            return JsonNodeFactory.instance.objectNode().put("ok", true).put("tool", nameValue);
        }
    }

    /** 需要审批的桩工具：记下自己有没有被真的执行过——审批挂起时它一次都不该跑。 */
    static class ApprovalStubTool implements AgentTool {
        private final String nameValue;
        final AtomicInteger executions = new AtomicInteger();

        ApprovalStubTool(String nameValue) {
            this.nameValue = nameValue;
        }

        @Override
        public String name() { return nameValue; }

        @Override
        public String description() { return "会对外发布 " + nameValue; }

        @Override
        public com.fasterxml.jackson.databind.JsonNode parametersSchema() {
            return JsonNodeFactory.instance.objectNode();
        }

        @Override
        public java.util.Optional<String> approvalReason(com.fasterxml.jackson.databind.JsonNode args) {
            return java.util.Optional.of("会对外发布内容，需要用户确认");
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode execute(Long userId,
                                                               com.fasterxml.jackson.databind.JsonNode args,
                                                               ProgressListener onProgress) {
            executions.incrementAndGet();
            return JsonNodeFactory.instance.objectNode().put("ok", true);
        }
    }

    /** A tool that always throws (name must match the failing test). */
    static class FailingTool implements AgentTool {
        private final String nameValue;

        FailingTool(String nameValue) { this.nameValue = nameValue; }

        @Override
        public String name() { return nameValue; }

        @Override
        public String description() { return "总是失败的工具"; }

        @Override
        public com.fasterxml.jackson.databind.JsonNode parametersSchema() {
            return JsonNodeFactory.instance.objectNode();
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode execute(Long userId,
                                                               com.fasterxml.jackson.databind.JsonNode args,
                                                               ProgressListener onProgress) {
            throw new IllegalStateException("工具执行失败");
        }
    }
}
