package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentEvent;
import com.xander.lab.agent.domain.AgentMessage;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import com.xander.lab.agent.infrastructure.AgentMessageMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies that extracted event projection keeps the legacy message and event contract. */
@ExtendWith(MockitoExtension.class)
class AgentEventProjectorTest {
    @Mock
    private AgentConversationMapper conversationMapper;
    @Mock
    private AgentMessageMapper messageMapper;
    @Mock
    private AgentEventService eventService;
    @Mock
    private TransactionTemplate transactionTemplate;

    private AgentEventProjector projector;

    @BeforeAll
    static void initializeMybatisMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), AgentConversation.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), AgentMessage.class);
    }

    @BeforeEach
    void setUp() {
        projector = new AgentEventProjector(conversationMapper, messageMapper, eventService, transactionTemplate);
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void activeMessageIsPersistedOnlyForTheCurrentRunningGeneration() {
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(messageMapper.insertForActiveRun(any(), eq(42L), eq(3L))).thenReturn(1);

        projector.saveActiveMessage(7L, 42L, 3L, "assistant", "answer", null, "完成");

        verify(messageMapper).insertForActiveRun(org.mockito.ArgumentMatchers.argThat(message ->
                message.getConversationId().equals(7L)
                        && message.getRunVersion().equals(3L)
                        && "assistant".equals(message.getRole())
                        && "answer".equals(message.getKind())
                        && "完成".equals(message.getContent())), eq(42L), eq(3L));
    }

    @Test
    void firstPlanProjectionCreatesTheLegacyPlanMessage() {
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(messageMapper.update(any(), any())).thenReturn(0);
        when(messageMapper.insertForActiveRun(any(), eq(42L), eq(3L))).thenReturn(1);

        projector.saveOrUpdateActivePlanMessage(7L, 42L, 3L, "[{\"title\":\"查资料\"}]");

        verify(messageMapper).insertForActiveRun(org.mockito.ArgumentMatchers.argThat(message ->
                "assistant".equals(message.getRole())
                        && "plan".equals(message.getKind())
                        && message.getRunVersion().equals(3L)
                        && message.getContent().contains("查资料")), eq(42L), eq(3L));
    }

    @Test
    void activeEventIsPersistedThenDeliveredToTheExistingListener() {
        AgentEvent event = new AgentEvent();
        event.setId(9L);
        when(eventService.storeActive(7L, 42L, 3L, "answer", "完成")).thenReturn(event);
        AtomicReference<AgentEvent> received = new AtomicReference<>();
        Consumer<AgentEvent> listener = received::set;

        AgentEvent projected = projector.appendActive(7L, 42L, 3L, "answer", "完成", listener);

        assertThat(projected).isSameAs(event);
        verify(eventService).deliver(event, listener);
    }

    /**
     * 回归：流式增量必须"先推送、再落库"。
     *
     * <p>用户看到首字不应该被一次数据库往返拖住——正文在上游吐出时就已经在内存里了。
     * 这里用调用顺序断言把方向钉死：deliver 必须先于 persistActiveSilently。</p>
     */
    @Test
    void liveDeltaIsDeliveredBeforeItIsPersisted() {
        AgentEvent live = new AgentEvent();
        live.setRunVersion(3L);
        live.setEventType("answer_delta");
        live.setDataContent("正文");
        when(eventService.liveEvent(7L, 3L, "answer_delta", "正文")).thenReturn(live);
        java.util.List<String> order = new java.util.ArrayList<>();
        // deliver 是"推送"这一步：这里按真实语义回调监听器，用于记录相对落库的先后。
        org.mockito.Mockito.doAnswer(invocation -> {
            order.add("deliver");
            invocation.getArgument(1, Consumer.class).accept(live);
            return null;
        }).when(eventService).deliver(eq(live), any());
        org.mockito.Mockito.doAnswer(invocation -> {
            order.add("persist");
            return null;
        }).when(eventService).persistActiveSilently(7L, 42L, 3L, "answer_delta", "正文");

        projector.appendLive(7L, 42L, 3L, "answer_delta", "正文", event -> { });

        assertThat(order).containsExactly("deliver", "persist");
    }

    /**
     * 回归：落库卡死/失败也不能让用户收不到已经生成好的正文。
     *
     * <p>这是用户明确指出的场景："如果落库卡死，用户永远收不到信息"。推送在落库之前已经完成，
     * 落库抛异常只降级为"这段增量没有可重放副本"，不能把整轮执行和已送达的正文一起废掉。</p>
     */
    @Test
    void liveDeltaSurvivesPersistenceFailureWithoutLosingDelivery() {
        AgentEvent live = new AgentEvent();
        live.setRunVersion(3L);
        live.setEventType("answer_delta");
        live.setDataContent("正文");
        when(eventService.liveEvent(7L, 3L, "answer_delta", "正文")).thenReturn(live);
        when(eventService.persistActiveSilently(7L, 42L, 3L, "answer_delta", "正文"))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db hung"));
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.getArgument(1, Consumer.class).accept(live);
            return null;
        }).when(eventService).deliver(eq(live), any());
        AtomicReference<AgentEvent> received = new AtomicReference<>();

        projector.appendLive(7L, 42L, 3L, "answer_delta", "正文", received::set);

        assertThat(received.get()).isSameAs(live);
    }

    /** 被新代次取代不是"落库故障"：本轮已作废，必须照常抛出让执行停止。 */
    @Test
    void liveDeltaStillPropagatesSupersededRuns() {
        AgentEvent live = new AgentEvent();
        when(eventService.liveEvent(7L, 3L, "answer_delta", "正文")).thenReturn(live);
        when(eventService.persistActiveSilently(7L, 42L, 3L, "answer_delta", "正文"))
                .thenThrow(new AgentRunSupersededException(7L, 3L));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        projector.appendLive(7L, 42L, 3L, "answer_delta", "正文", event -> { }))
                .isInstanceOf(AgentRunSupersededException.class);
    }

    @Test
    void toolProgressPayloadKeepsTheOptionalStageDelimiterContract() {
        JsonNode payload = projector.progressPayload("blog_generate", null, "draft|正在生成");

        assertThat(payload.path("tool").asText()).isEqualTo("blog_generate");
        assertThat(payload.path("stage").asText()).isEqualTo("draft");
        assertThat(payload.path("message").asText()).isEqualTo("正在生成");
        // 身份为空时**不写这个键**，而不是写成 null：前端用 `?? 工具名` 回退，
        // 一个显式的 null 会让 `payload.invocationId ?? tool` 仍然回退（null 触发 ??），
        // 但多一个空键会让"这条事件没有身份"与"身份是空字符串"在日志里分不清。
        assertThat(payload.has("invocationId")).isFalse();
    }

    @Test
    void toolProgressPayloadCarriesTheInvocationIdWhenKnown() {
        JsonNode payload = projector.progressPayload("query_knowledge", "inv-1", "正在检索");

        assertThat(payload.path("invocationId").asText()).isEqualTo("inv-1");
        assertThat(payload.path("message").asText()).isEqualTo("正在检索");
        assertThat(payload.has("stage")).isFalse();
    }
}
