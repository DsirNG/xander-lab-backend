package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentEvent;
import com.xander.lab.agent.domain.AgentMessage;
import com.xander.lab.agent.domain.AgentPlanItem;
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
                transactionTemplate, eventService, agentLeaseRenewer, mock(BillingService.class), objectMapper,
                planService, memoryService, new AgentTurnEvaluator());
        org.mockito.Mockito.lenient().doReturn(leaseRenewal).when(agentLeaseRenewer)
                .scheduleAtFixedRate(any(Runnable.class), any(Long.class), any(Long.class), any());
        org.mockito.Mockito.lenient().when(properties.getMaxSteps()).thenReturn(10);
        org.mockito.Mockito.lenient().when(properties.getMaxToolFailures()).thenReturn(2);
        org.mockito.Mockito.lenient().when(properties.getContextMessageLimit()).thenReturn(30);
        org.mockito.Mockito.lenient().when(properties.getStaleConversationMinutes()).thenReturn(30);
        org.mockito.Mockito.lenient().when(properties.getMaxReflections()).thenReturn(1);
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
                transactionTemplate, eventService, agentLeaseRenewer, mock(BillingService.class), objectMapper,
                planService, memoryService, new AgentTurnEvaluator());
        AgentConversation ready = conversation("running");
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(ready);

        AgentConversation result = service.executeTurn(7L, 42L, "触发失败", ignored -> { });

        assertThat(result.getStatus()).isEqualTo("ready");
        verify(modelClient).decideNextStepStream(any(), any(), any(), any());
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

        service.executeTurn(7L, 42L, "随便聊聊", ignored -> { });

        var context = org.mockito.ArgumentCaptor.forClass(String.class);
        var toolDescriptions = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(modelClient).decideNextStepStream(
                context.capture(), toolDescriptions.capture(), any(), any());
        assertThat(toolDescriptions.getValue()).contains("blog_generate");
        assertThat(context.getValue())
                .contains("以下是智能体与用户的对话记录")
                .endsWith("请根据以上信息决定下一步：需要动手就返回 action，已经真正做完就返回 done=true 的最终回复。");
    }

    @Test
    void reflectionGateBlocksAPrematureDoneAndFeedsTheCritiqueBack() {
        stubTransactions();
        when(planService.load(any())).thenReturn(List.of(
                new AgentPlanItem("查一下知识库", "IN_PROGRESS", "")));
        when(planService.render(any())).thenReturn("1. 查一下知识库 (IN_PROGRESS)");
        ObjectNode premature = decisionNode(true, "", null, null);
        premature.put("reply", "都办好了");
        ObjectNode settled = decisionNode(true, "", null, null);
        settled.put("reply", "查询失败，我没能拿到知识库数据");
        when(modelClient.decideNextStepStream(any(), any(), any(), any()))
                .thenReturn(call(premature), call(settled));
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));
        List<String> eventTypes = new java.util.ArrayList<>();

        AgentConversation result = service.executeTurn(
                7L, 42L, "帮我复习", event -> eventTypes.add(event.getEventType()));

        assertThat(result.getStatus()).isEqualTo("ready");
        assertThat(eventTypes).containsSubsequence("reflection", "answer");
        var contexts = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(modelClient, org.mockito.Mockito.times(2))
                .decideNextStepStream(contexts.capture(), any(), any(), any());
        assertThat(contexts.getAllValues().get(0)).doesNotContain("【自检结果】");
        assertThat(contexts.getAllValues().get(1))
                .contains("【自检结果】")
                .contains("还有 1 个步骤没有收口")
                .contains("查一下知识库");
    }

    @Test
    void reflectionBudgetIsBoundedSoTheTurnCannotSpinForever() {
        stubTransactions();
        when(planService.load(any())).thenReturn(List.of(
                new AgentPlanItem("永远没做完的步骤", "PENDING", "")));
        ObjectNode premature = decisionNode(true, "", null, null);
        premature.put("reply", "都办好了");
        when(modelClient.decideNextStepStream(any(), any(), any(), any())).thenReturn(call(premature));
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));

        AgentConversation result = service.executeTurn(7L, 42L, "帮我复习", ignored -> { });

        assertThat(result.getStatus()).isEqualTo("ready");
        // maxReflections=1：驳回一次后放行，两次模型调用而不是打满 maxSteps。
        verify(modelClient, org.mockito.Mockito.times(2))
                .decideNextStepStream(any(), any(), any(), any());
        verify(messageMapper).insertForActiveRun(
                argThatMessage("assistant", "answer", null), eq(42L), eq(1L));
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

    private AgentMessage argThatMessage(String role, String kind, String toolName) {
        return org.mockito.ArgumentMatchers.argThat(message ->
                role.equals(message.getRole())
                        && kind.equals(message.getKind())
                        && java.util.Objects.equals(toolName, message.getToolName()));
    }

    private void stubTransactions() {
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        doAnswer(invocation -> {
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
