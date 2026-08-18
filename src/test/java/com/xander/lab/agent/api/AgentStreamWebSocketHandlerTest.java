package com.xander.lab.agent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.application.AgentEventService;
import com.xander.lab.agent.domain.AgentEvent;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

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

    private AgentEvent event(long id, String type, String data) {
        AgentEvent event = new AgentEvent();
        event.setId(id);
        event.setEventType(type);
        event.setDataContent(data);
        return event;
    }

    private AgentStreamWebSocketHandler handler(AgentEventService eventService) {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        org.mockito.Mockito.doReturn(mock(ScheduledFuture.class))
                .when(scheduler).scheduleAtFixedRate(
                        any(Runnable.class), any(Instant.class), any(Duration.class));
        return new AgentStreamWebSocketHandler(
                eventService, new ObjectMapper(), Runnable::run, scheduler);
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
    void streamsEventsAsJsonFramesAndClosesAfterTerminal() throws Exception {
        AgentEventService eventService = mock(AgentEventService.class);
        doAnswer(invocation -> {
            Consumer<AgentEvent> sink = invocation.getArgument(4);
            sink.accept(event(10L, "answer_delta", "你好"));
            sink.accept(event(11L, "complete", "{\"status\":\"ready\"}"));
            return null;
        }).when(eventService).follow(eq(7L), eq(42L), eq(9L), eq(0L), any());
        WebSocketSession session = session(7L, 9L);

        handler(eventService).afterConnectionEstablished(session);

        verify(session).sendMessage(argThat((TextMessage message) -> {
            String payload = message.getPayload();
            return payload.contains("\"event\":\"answer_delta\"")
                    && payload.contains("\"data\":\"你好\"")
                    && payload.contains("\"id\":10");
        }));
        verify(session).sendMessage(argThat((TextMessage message) ->
                message.getPayload().contains("\"event\":\"complete\"")));
        verify(session).close(argThat(status -> status.getCode() == CloseStatus.NORMAL.getCode()));
    }

    @Test
    void sendsPongForClientPing() throws Exception {
        WebSocketSession session = session(7L, 9L);

        handler(mock(AgentEventService.class)).handleTextMessage(
                session, new TextMessage("{\"event\":\"ping\"}"));

        verify(session).sendMessage(argThat((TextMessage message) ->
                message.getPayload().contains("\"event\":\"pong\"")));
    }

    @Test
    void rejectsSessionWithoutHandshakeAttributes() throws Exception {
        AgentEventService eventService = mock(AgentEventService.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(Map.of());

        handler(eventService).afterConnectionEstablished(session);

        verify(session).close(argThat(status ->
                status.getCode() == CloseStatus.NOT_ACCEPTABLE.getCode()));
        verify(eventService, never()).follow(any(), any(), anyLong(), anyLong(), any());
    }
}