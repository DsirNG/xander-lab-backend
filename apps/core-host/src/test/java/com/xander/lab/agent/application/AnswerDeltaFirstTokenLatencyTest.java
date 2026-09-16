package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentEvent;
import com.xander.lab.agent.domain.AgentMessage;
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
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 首字延迟（time-to-first-visible-delta）的回归防线。
 *
 * <p>"先推送、后落库"的全部意义是：用户看到第一个字的时刻不该包含任何数据库往返。
 * 这里把落库人为放慢，把"落库是否挡在推送之前"变成一个可观测的差异——
 * 若哪天顺序被改回"先落库"，首字就会被整段落库延迟吃掉，这几条用例会立刻变红。</p>
 */
@ExtendWith(MockitoExtension.class)
class AnswerDeltaFirstTokenLatencyTest {
    /** 人为的落库延迟，用于放大"落库是否阻塞推送"的差异。 */
    private static final long SLOW_PERSIST_MILLIS = 300L;

    @Mock private AgentConversationMapper conversationMapper;
    @Mock private AgentMessageMapper messageMapper;
    @Mock private AgentModelClient modelClient;
    @Mock private AgentEventMapper eventMapper;
    @Mock private AgentConversationProperties properties;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private ScheduledExecutorService agentLeaseRenewer;
    @Mock private ScheduledFuture<?> leaseRenewal;
    @Mock private AgentPlanService planService;
    @Mock private AgentMemoryService memoryService;

    private AgentConversationService service;

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentConversation.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentMessage.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentEvent.class);
    }

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        BillingService billingService = mock(BillingService.class);
        AgentToolRegistry registry = new AgentToolRegistry(List.of());
        AgentEventService eventService =
                new AgentEventService(eventMapper, conversationMapper, objectMapper, new AgentEventBus());
        service = new AgentConversationService(
                conversationMapper, messageMapper, modelClient, registry, properties,
                transactionTemplate, eventService, agentLeaseRenewer, billingService, objectMapper,
                planService, memoryService, new AgentQuizService(objectMapper));

        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        lenient().doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any());
        lenient().doReturn(leaseRenewal).when(agentLeaseRenewer)
                .scheduleAtFixedRate(any(Runnable.class), any(Long.class), any(Long.class), any());

        lenient().when(conversationMapper.update(any(), any())).thenReturn(1);
        lenient().when(conversationMapper.selectById(7L)).thenReturn(conversation("running"));
        lenient().when(messageMapper.insertForActiveRun(any(), any(), any(Long.class))).thenReturn(1);
        lenient().when(eventMapper.selectList(any())).thenReturn(List.of());
        lenient().when(planService.load(any())).thenAnswer(invocation -> List.of());
        lenient().when(properties.getMaxSteps()).thenReturn(10);
        lenient().when(properties.getMaxToolFailures()).thenReturn(2);
        lenient().when(properties.getContextMessageLimit()).thenReturn(30);
    }

    /**
     * 落库慢时首字仍然立刻可见。
     *
     * <p>每次 INSERT 睡 {@link #SLOW_PERSIST_MILLIS}，而推送本身不碰数据库；
     * 断言首字到达耗时显著小于一次慢落库，证明确实没有"等落库"这一步。</p>
     */
    @Test
    void firstDeltaArrivesWithoutWaitingForASlowDatabaseWrite() {
        slowPersistence();
        ObjectNode decision = doneDecision("首段正文");
        when(modelClient.decideNextStepStream(any(), any(), any(), any())).thenAnswer(invocation -> {
            Consumer<String> deltas = invocation.getArgument(2);
            deltas.accept("首段正文");
            return ModelCallResult.of(decision, ModelUsage.NONE);
        });
        AtomicLong firstDeltaNanos = new AtomicLong(0);
        long startedNanos = System.nanoTime();

        service.executeTurn(7L, 42L, "你好", event -> {
            if ("answer_delta".equals(event.getEventType())) {
                firstDeltaNanos.compareAndSet(0, System.nanoTime());
            }
        });

        assertThat(firstDeltaNanos.get()).isNotZero();
        long firstDeltaElapsedMillis = (firstDeltaNanos.get() - startedNanos) / 1_000_000L;
        // 关键断言：首字没有被那次 300ms 的落库往返吃掉。
        assertThat(firstDeltaElapsedMillis)
                .as("首字延迟不应包含落库往返")
                .isLessThan(SLOW_PERSIST_MILLIS);
    }

    /**
     * 落库一直失败时，首字依然要送到用户手里。
     *
     * <p>这是用户提出的"落库卡死用户永远收不到信息"的直接防线：把 INSERT 打挂，
     * 用户仍必须收到那段正文。同时这条用例也覆盖了"落库故障与当前异常是同一个实例"的场景
     * ——那曾让 {@code addSuppressed} 抛 self-suppression，把真实原因替换成参数错误。</p>
     */
    @Test
    void firstDeltaStillReachesTheUserWhenPersistenceKeepsFailing() {
        when(eventMapper.insertForRun(any(), any(), anyBoolean()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db hung"));
        ObjectNode decision = doneDecision("正文");
        when(modelClient.decideNextStepStream(any(), any(), any(), any())).thenAnswer(invocation -> {
            Consumer<String> deltas = invocation.getArgument(2);
            deltas.accept("正文");
            return ModelCallResult.of(decision, ModelUsage.NONE);
        });
        List<String> deltas = new ArrayList<>();

        // 整轮失败会被 coordinator 收口成终态错误；但用户已经在失败前看到了正文。
        // 这里断言"推送先于落库"，所以即使执行以异常收尾，正文也必须已经送达。
        try {
            service.executeTurn(7L, 42L, "你好", event -> {
                if ("answer_delta".equals(event.getEventType())) deltas.add(event.getDataContent());
            });
        } catch (RuntimeException expectedTurnFailure) {
            // 落库全挂时整轮以失败收尾是正确行为；关键是被推送的内容不受影响。
        }

        assertThat(deltas).contains("正文");
    }

    /** 一次回答里上游吐出 40+ 个分片时，推送次数应被时间窗压到极少，而不是逐片一次。 */
    @Test
    void burstOfUpstreamDeltasIsCoalescedBeforeReachingTheUser() {
        AtomicInteger eventIds = new AtomicInteger();
        lenient().doAnswer(invocation -> {
            AgentEvent event = invocation.getArgument(0);
            event.setId((long) eventIds.incrementAndGet());
            return 1;
        }).when(eventMapper).insertForRun(any(), any(), anyBoolean());
        ObjectNode decision = doneDecision("长回答".repeat(20));
        when(modelClient.decideNextStepStream(any(), any(), any(), any())).thenAnswer(invocation -> {
            Consumer<String> deltas = invocation.getArgument(2);
            deltas.accept("首");
            for (int index = 0; index < 40; index++) deltas.accept("片" + index);
            return ModelCallResult.of(decision, ModelUsage.NONE);
        });
        List<String> deltas = new ArrayList<>();

        service.executeTurn(7L, 42L, "你好", event -> {
            if ("answer_delta".equals(event.getEventType())) deltas.add(event.getDataContent());
        });

        assertThat(deltas).hasSizeLessThanOrEqualTo(2);
        assertThat(String.join("", deltas)).isEqualTo("首" + java.util.stream.IntStream.range(0, 40)
                .mapToObj(index -> "片" + index).collect(java.util.stream.Collectors.joining()));
    }

    /**
     * 回归：落库整体不可用时，终态收口不能把真实异常替换成 "Self-suppression not permitted"。
     *
     * <p>数据库挂掉时，"业务失败"和"失败收口的落库"走的是同一条 INSERT 链路，
     * {@code runLifecycle.fail} 抛回来的就是正在传播的<b>同一个异常实例</b>。
     * 此时直接 {@code addSuppressed} 自己会抛 IllegalArgumentException，
     * 调用方拿到的变成一句毫无信息量的参数错误，真正的"数据库连不上"反而丢了。</p>
     *
     * <p>用 mock 让 INSERT 抛出一个预置的同一实例来复现这个场景——
     * 这正是 {@code DataAccessResourceFailureException} 由同一连接池异常对象冒泡出来的形态。</p>
     */
    @Test
    void terminalFailureKeepsTheRealCauseWhenPersistenceIsAlsoBroken() {
        org.springframework.dao.DataAccessResourceFailureException shared =
                new org.springframework.dao.DataAccessResourceFailureException("db hung");
        // 整轮的执行异常与失败收口的落库异常是同一个实例：同一条 INSERT 链路冒出来的。
        when(eventMapper.insertForRun(any(), any(), anyBoolean())).thenThrow(shared);
        when(modelClient.decideNextStepStream(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("模型侧可识别错误"));

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() ->
                service.executeTurn(7L, 42L, "你好", event -> { }));

        assertThat(thrown).isNotNull();
        // 绝不能是 addSuppressed 自己吞自己产生的参数错误。
        assertThat(thrown).isNotInstanceOf(IllegalArgumentException.class);
        assertThat(thrown.getMessage()).doesNotContain("Self-suppression");
    }

    /**
     * 更直接的一版：让失败收口的 {@code runLifecycle.fail} 抛回<b>与业务异常完全相同的实例</b>，
     * 断言 coordinator 不会因此把异常换成 self-suppression 参数错误。
     *
     * <p>数据库整体不可用时就是这么回事：同一条 INSERT 链路冒出来的异常对象，
     * 既让整轮失败，又在收口时再次被抛出——它就是"同一个实例"。</p>
     */
    @Test
    void coordinatorDoesNotSelfSuppressWhenTheSameExceptionComesBack() {
        AgentRunLifecycle lifecycle = mock(AgentRunLifecycle.class);
        RuntimeException shared = new RuntimeException("db hung");
        when(lifecycle.startLeaseRenewal(any(), any(), any(Long.class)))
                .thenReturn(mock(ScheduledFuture.class));
        when(lifecycle.fail(any(), any(), any(Long.class), any())).thenThrow(shared);
        AgentTurnCoordinator coordinator = new AgentTurnCoordinator(
                conversationMapper, null, lifecycle, null, null, null);

        RuntimeException thrown = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> coordinator.execute(7L, 42L, 1L, event -> { }, () -> {
                    throw shared;
                }), RuntimeException.class);

        assertThat(thrown).isSameAs(shared);
        assertThat(thrown.getMessage()).doesNotContain("Self-suppression");
    }

    /** 让 INSERT 变慢，模拟"落库很慢但最终成功"。 */
    private void slowPersistence() {
        lenient().doAnswer(invocation -> {
            Thread.sleep(SLOW_PERSIST_MILLIS);
            AgentEvent event = invocation.getArgument(0);
            event.setId(9L);
            return 1;
        }).when(eventMapper).insertForRun(any(), any(), anyBoolean());
    }

    private static ObjectNode doneDecision(String reply) {
        ObjectNode decision = JsonNodeFactory.instance.objectNode();
        decision.put("done", true);
        decision.put("thought", "");
        decision.put("reply", reply);
        decision.putNull("action");
        return decision;
    }

    private static AgentConversation conversation(String status) {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(7L);
        conversation.setUserId(42L);
        conversation.setStatus(status);
        conversation.setRunVersion(1L);
        conversation.setUpdatedAt(LocalDateTime.now());
        return conversation;
    }
}
