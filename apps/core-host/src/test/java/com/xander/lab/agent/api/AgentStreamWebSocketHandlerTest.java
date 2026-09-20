package com.xander.lab.agent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.application.AgentEventBus;
import com.xander.lab.agent.application.AgentEventService;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentEvent;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import com.xander.lab.common.security.ConnectionRegistry;
import com.xander.lab.common.security.SecurityStateCache;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentStreamWebSocketHandlerTest {

    private AgentEvent event(long id, long runVersion, String type, String data) {
        AgentEvent event = new AgentEvent();
        event.setId(id);
        event.setRunVersion(runVersion);
        event.setEventType(type);
        event.setDataContent(data);
        return event;
    }

    private AgentStreamWebSocketHandler handler(AgentEventService eventService, AgentEventBus eventBus,
                                                AgentConversationMapper conversationMapper) {
        return handler(eventService, eventBus, conversationMapper,
                AgentStreamWebSocketHandler.SUPERSEDED_CHECK_MILLIS);
    }

    private AgentStreamWebSocketHandler handler(AgentEventService eventService, AgentEventBus eventBus,
                                                AgentConversationMapper conversationMapper, long checkMillis) {
        return new AgentStreamWebSocketHandler(
                eventService, eventBus, new ObjectMapper(), Runnable::run, heartbeatScheduler(),
                conversationMapper, checkMillis);
    }

    /** 与生产同构的构造：注入共享的 cache / registry，用于验证封禁链路。 */
    private AgentStreamWebSocketHandler handler(AgentEventService eventService, AgentEventBus eventBus,
                                                AgentConversationMapper conversationMapper,
                                                SecurityStateCache securityStateCache,
                                                ConnectionRegistry connectionRegistry) {
        return handler(eventService, eventBus, conversationMapper, securityStateCache,
                connectionRegistry, Runnable::run,
                AgentStreamWebSocketHandler.SUPERSEDED_CHECK_MILLIS);
    }

    private AgentStreamWebSocketHandler handler(AgentEventService eventService, AgentEventBus eventBus,
                                                AgentConversationMapper conversationMapper,
                                                SecurityStateCache securityStateCache,
                                                ConnectionRegistry connectionRegistry,
                                                org.springframework.core.task.TaskExecutor executor,
                                                long checkMillis) {
        return new AgentStreamWebSocketHandler(
                eventService, eventBus, new ObjectMapper(), executor, heartbeatScheduler(),
                conversationMapper, securityStateCache, connectionRegistry, checkMillis);
    }

    private TaskScheduler heartbeatScheduler() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        org.mockito.Mockito.doReturn(mock(ScheduledFuture.class))
                .when(scheduler).scheduleAtFixedRate(
                        any(Runnable.class), any(Instant.class), any(Duration.class));
        return scheduler;
    }

    private WebSocketSession session(long conversationId, Long runVersion) throws Exception {
        return session(conversationId, runVersion, 42L);
    }

    /**
     * 可变的 WS 会话替身：{@code close(...)} 之后 {@code isOpen()} 变 false。
     *
     * <p>这一点很重要：真实容器关闭连接后 {@code isOpen()} 就会变 false，
     * 而默认 mock 永远返回 true，会掩盖「关闭后仍继续发送」这类缺陷。</p>
     */
    private WebSocketSession session(long conversationId, Long runVersion, Long userId)
            throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        AtomicBoolean open = new AtomicBoolean(true);
        when(session.isOpen()).thenAnswer(invocation -> open.get());
        doAnswer(invocation -> {
            open.set(false);
            return null;
        }).when(session).close(any(CloseStatus.class));
        when(session.getAttributes()).thenReturn(new ConcurrentHashMap<>(Map.of(
                AgentStreamAuthHandshakeInterceptor.ATTR_USER_ID, userId,
                AgentStreamAuthHandshakeInterceptor.ATTR_CONVERSATION_ID, conversationId,
                AgentStreamAuthHandshakeInterceptor.ATTR_RUN_VERSION, runVersion)));
        return session;
    }

    @Test
    void streamsReplayedEventsAsJsonFramesAndClosesAfterTerminal() throws Exception {
        AgentEventService eventService = mock(AgentEventService.class);
        when(eventService.listAfter(7L, 9L, 0L)).thenReturn(List.of(
                event(10L, 9L, "answer_delta", "你好"),
                event(11L, 9L, "complete", "{\"status\":\"ready\"}")));
        WebSocketSession wsSession = session(7L, 9L);

        handler(eventService, new AgentEventBus(), mock(AgentConversationMapper.class))
                .afterConnectionEstablished(wsSession);

        verify(wsSession).sendMessage(argThat((TextMessage message) -> {
            String payload = message.getPayload();
            return payload.contains("\"event\":\"answer_delta\"")
                    && payload.contains("\"data\":\"你好\"")
                    && payload.contains("\"id\":10");
        }));
        verify(wsSession).sendMessage(argThat((TextMessage message) ->
                message.getPayload().contains("\"event\":\"complete\"")));
        verify(wsSession).close(argThat(status -> status.getCode() == CloseStatus.NORMAL.getCode()));
    }

    @Test
    void deliversLiveEventsFromBusWithoutDatabasePolling() throws Exception {
        AgentEventBus eventBus = new AgentEventBus();
        AgentEventService eventService = mock(AgentEventService.class);
        AtomicInteger listCalls = new AtomicInteger();
        doAnswer(invocation -> {
            // 订阅已建立：模拟执行端落库后即时广播，库中暂无可重放的新事件。
            if (listCalls.incrementAndGet() == 1) {
                eventBus.publish(7L, event(10L, 9L, "answer_delta", "实时"));
                eventBus.publish(7L, event(11L, 9L, "complete", "{}"));
            }
            return List.of();
        }).when(eventService).listAfter(eq(7L), eq(9L), eq(0L));
        WebSocketSession wsSession = session(7L, 9L);

        handler(eventService, eventBus, mock(AgentConversationMapper.class))
                .afterConnectionEstablished(wsSession);

        verify(wsSession).sendMessage(argThat((TextMessage message) ->
                message.getPayload().contains("\"data\":\"实时\"")));
        verify(wsSession).sendMessage(argThat((TextMessage message) ->
                message.getPayload().contains("\"event\":\"complete\"")));
        verify(wsSession).close(argThat(status -> status.getCode() == CloseStatus.NORMAL.getCode()));
    }

    @Test
    void deduplicatesEventsDeliveredByBothBusAndReplay() throws Exception {
        AgentEventBus eventBus = new AgentEventBus();
        AgentEventService eventService = mock(AgentEventService.class);
        doAnswer(invocation -> {
            // 总线先直推了 delta=10；随后重放又从库里读到同一条，必须只发一次。
            eventBus.publish(7L, event(10L, 9L, "answer_delta", "重复"));
            return List.of(
                    event(10L, 9L, "answer_delta", "重复"),
                    event(11L, 9L, "complete", "{}"));
        }).when(eventService).listAfter(eq(7L), eq(9L), eq(0L));
        WebSocketSession wsSession = session(7L, 9L);

        handler(eventService, eventBus, mock(AgentConversationMapper.class))
                .afterConnectionEstablished(wsSession);

        // delta 只出现一次；complete 正常收尾。
        verify(wsSession, org.mockito.Mockito.times(2)).sendMessage(any(TextMessage.class));
        verify(wsSession).sendMessage(argThat((TextMessage message) ->
                message.getPayload().contains("\"data\":\"重复\"")));
        verify(wsSession).sendMessage(argThat((TextMessage message) ->
                message.getPayload().contains("\"event\":\"complete\"")));
    }

    @Test
    void ignoresEventsFromOtherRunVersions() throws Exception {
        AgentEventBus eventBus = new AgentEventBus();
        AgentEventService eventService = mock(AgentEventService.class);
        when(eventService.listAfter(7L, 9L, 0L)).thenReturn(List.of());
        when(eventService.latestTerminal(7L, 9L)).thenReturn(null);
        AgentConversation superseded = new AgentConversation();
        superseded.setUserId(42L);
        superseded.setRunVersion(10L);
        superseded.setStatus("running");
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        when(conversationMapper.selectById(7L)).thenReturn(superseded);
        WebSocketSession wsSession = session(7L, 9L);

        handler(eventService, eventBus, conversationMapper, 10L).afterConnectionEstablished(wsSession);

        // 其他代次事件不得推给本订阅；被取代后直接收尾关闭。
        verify(wsSession, never()).sendMessage(argThat((TextMessage message) ->
                message.getPayload().contains("answer_delta")));
        verify(wsSession).close(argThat(status -> status.getCode() == CloseStatus.NORMAL.getCode()));
    }

    @Test
    void sendsPongForClientPing() throws Exception {
        WebSocketSession wsSession = session(7L, 9L);

        handler(mock(AgentEventService.class), new AgentEventBus(), mock(AgentConversationMapper.class))
                .handleTextMessage(wsSession, new TextMessage("{\"event\":\"ping\"}"));

        verify(wsSession).sendMessage(argThat((TextMessage message) ->
                message.getPayload().contains("\"event\":\"pong\"")));
    }

    @Test
    void rejectsSessionWithoutHandshakeAttributes() throws Exception {
        AgentEventService eventService = mock(AgentEventService.class);
        WebSocketSession wsSession = mock(WebSocketSession.class);
        when(wsSession.isOpen()).thenReturn(true);
        when(wsSession.getAttributes()).thenReturn(Map.of());

        handler(eventService, new AgentEventBus(), mock(AgentConversationMapper.class))
                .afterConnectionEstablished(wsSession);

        verify(wsSession).close(argThat(status ->
                status.getCode() == CloseStatus.NOT_ACCEPTABLE.getCode()));
        verify(eventService, never()).listAfter(any(), anyLong(), anyLong());
    }

    // ---------- 账号封禁必须终止已建立的 WS 订阅 ----------

    @Test
    void closesWith4403WhenAccountAlreadyDisabled() throws Exception {
        SecurityStateCache cache = new SecurityStateCache();
        cache.markDisabled(42L, 7L);
        ConnectionRegistry registry = new ConnectionRegistry();
        AgentEventService eventService = mock(AgentEventService.class);
        WebSocketSession wsSession = session(7L, 9L);

        handler(eventService, new AgentEventBus(), mock(AgentConversationMapper.class), cache, registry)
                .afterConnectionEstablished(wsSession);

        // 入口侧就要拦住：封禁账号不应该还能建立新的订阅，更不该走到重放。
        verify(wsSession).close(argThat(status ->
                status.getCode() == AgentStreamWebSocketHandler.CLOSE_CODE_ACCOUNT_DISABLED
                        && AgentStreamWebSocketHandler.CLOSE_REASON_ACCOUNT_DISABLED
                                .equals(status.getReason())));
        verify(eventService, never()).listAfter(any(), anyLong(), anyLong());
        assertEquals(0, registry.activeCount());
    }

    @Test
    void closesWith4403AndStopsPushingWhenAccountDisabledMidStream() throws Exception {
        SecurityStateCache cache = new SecurityStateCache();
        ConnectionRegistry registry = new ConnectionRegistry();
        AgentEventService eventService = mock(AgentEventService.class);
        doAnswer(invocation -> {
            // 流进行中账号被封禁：下一次出站必须被拦住，且不再推送任何帧。
            cache.markDisabled(42L, 8L);
            return List.of(event(10L, 9L, "answer_delta", "不该发出"));
        }).when(eventService).listAfter(eq(7L), eq(9L), eq(0L));
        WebSocketSession wsSession = session(7L, 9L);

        handler(eventService, new AgentEventBus(), mock(AgentConversationMapper.class), cache, registry)
                .afterConnectionEstablished(wsSession);

        verify(wsSession, never()).sendMessage(any(TextMessage.class));
        verify(wsSession).close(argThat(status ->
                status.getCode() == AgentStreamWebSocketHandler.CLOSE_CODE_ACCOUNT_DISABLED));
        assertEquals(0, registry.activeCount());
    }

    @Test
    void reconcilerCloseAllClosesEstablishedWsWith4403() throws Exception {
        SecurityStateCache cache = new SecurityStateCache();
        ConnectionRegistry registry = new ConnectionRegistry();
        AgentEventService eventService = mock(AgentEventService.class);
        when(eventService.listAfter(7L, 9L, 0L)).thenReturn(List.of());
        AgentConversation running = new AgentConversation();
        running.setUserId(42L);
        running.setRunVersion(9L);
        running.setStatus("running");
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        when(conversationMapper.selectById(7L)).thenReturn(running);
        WebSocketSession wsSession = session(7L, 9L);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            handler(eventService, new AgentEventBus(), conversationMapper, cache, registry,
                    executor::execute, 50L)
                    .afterConnectionEstablished(wsSession);

            // 连接必须已登记，reconciler 才能在本实例上找到它。
            assertEquals(1, registry.activeCount(42L));

            // 模拟 SecurityStateReconciler 命中封禁：手段 A，主动断开已建立的连接。
            cache.markDisabled(42L, 3L);
            assertEquals(1, registry.closeAll(42L,
                    AgentStreamWebSocketHandler.CLOSE_REASON_ACCOUNT_DISABLED));

            verify(wsSession).close(argThat(status ->
                    status.getCode() == AgentStreamWebSocketHandler.CLOSE_CODE_ACCOUNT_DISABLED
                            && AgentStreamWebSocketHandler.CLOSE_REASON_ACCOUNT_DISABLED
                                    .equals(status.getReason())));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void unregistersFromConnectionRegistryAfterTerminal() throws Exception {
        ConnectionRegistry registry = new ConnectionRegistry();
        AgentEventService eventService = mock(AgentEventService.class);
        when(eventService.listAfter(7L, 9L, 0L)).thenReturn(List.of(
                event(11L, 9L, "complete", "{}")));
        WebSocketSession wsSession = session(7L, 9L);

        handler(eventService, new AgentEventBus(), mock(AgentConversationMapper.class),
                new SecurityStateCache(), registry)
                .afterConnectionEstablished(wsSession);

        // 自然收尾后不得残留句柄，否则封禁时会去关一条已死的连接（并掩盖泄漏）。
        assertEquals(0, registry.activeCount(42L));
        assertEquals(0, registry.activeCountByKind(ConnectionRegistry.KIND_WS));
    }

    @Test
    void rejectsPongAndClosesWith4403ForDisabledAccount() throws Exception {
        SecurityStateCache cache = new SecurityStateCache();
        cache.markDisabled(42L, 1L);
        WebSocketSession wsSession = session(7L, 9L);

        handler(mock(AgentEventService.class), new AgentEventBus(),
                mock(AgentConversationMapper.class), cache, new ConnectionRegistry())
                .handleTextMessage(wsSession, new TextMessage("{\"event\":\"ping\"}"));

        // 封禁后不再回 pong：否则客户端会误判链路健康并继续重连。
        verify(wsSession, never()).sendMessage(any(TextMessage.class));
        verify(wsSession).close(argThat(status ->
                status.getCode() == AgentStreamWebSocketHandler.CLOSE_CODE_ACCOUNT_DISABLED));
    }
}
