package com.xander.lab.agent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.application.AgentEventBus;
import com.xander.lab.agent.application.AgentEventService;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentEvent;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

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
        TaskScheduler scheduler = mock(TaskScheduler.class);
        org.mockito.Mockito.doReturn(mock(ScheduledFuture.class))
                .when(scheduler).scheduleAtFixedRate(
                        any(Runnable.class), any(Instant.class), any(Duration.class));
        return new AgentStreamWebSocketHandler(
                eventService, eventBus, new ObjectMapper(), Runnable::run, scheduler, conversationMapper,
                checkMillis);
    }

    private WebSocketSession session(long conversationId, Long runVersion) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(Map.of(
                AgentStreamAuthHandshakeInterceptor.ATTR_USER_ID, 42L,
                AgentStreamAuthHandshakeInterceptor.ATTR_CONVERSATION_ID, conversationId,
                AgentStreamAuthHandshakeInterceptor.ATTR_RUN_VERSION, runVersion));
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
}
