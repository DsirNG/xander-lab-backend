package com.xander.lab.agent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.agent.application.AgentEventService;
import com.xander.lab.agent.domain.AgentEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 智能体会话事件流的 WebSocket 订阅端：小程序无法读取 SSE 流，
 * 改用 WS 逐事件推送（与 SSE 共用 {@link AgentEventService#follow} 的持久化事件重放逻辑）。
 * 帧格式与 SSE 事件一致：{"id":事件游标,"event":"answer_delta","data":...}。
 * 一轮终态（complete/error）推送后即关闭连接，下一轮由客户端重新建立。
 */
@Slf4j
public class AgentStreamWebSocketHandler extends TextWebSocketHandler {

    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(20);

    private final AgentEventService eventService;
    private final ObjectMapper objectMapper;
    private final TaskExecutor agentSseExecutor;
    private final TaskScheduler agentHeartbeatScheduler;

    public AgentStreamWebSocketHandler(AgentEventService eventService,
                                       ObjectMapper objectMapper,
                                       @Qualifier("agentSseExecutor") TaskExecutor agentSseExecutor,
                                       @Qualifier("agentHeartbeatScheduler") TaskScheduler agentHeartbeatScheduler) {
        this.eventService = eventService;
        this.objectMapper = objectMapper;
        this.agentSseExecutor = agentSseExecutor;
        this.agentHeartbeatScheduler = agentHeartbeatScheduler;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get(AgentStreamAuthHandshakeInterceptor.ATTR_USER_ID);
        Long conversationId = (Long) session.getAttributes().get(AgentStreamAuthHandshakeInterceptor.ATTR_CONVERSATION_ID);
        Long runVersion = (Long) session.getAttributes().get(AgentStreamAuthHandshakeInterceptor.ATTR_RUN_VERSION);
        if (userId == null || conversationId == null || runVersion == null) {
            closeQuietly(session, CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        AtomicLong lastSentAt = new AtomicLong(System.currentTimeMillis());
        ScheduledFuture<?> heartbeat = scheduleHeartbeat(session, lastSentAt);
        try {
            agentSseExecutor.execute(() -> stream(session, userId, conversationId, runVersion, heartbeat, lastSentAt));
        } catch (RuntimeException exception) {
            heartbeat.cancel(false);
            closeQuietly(session, CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 客户端保活 ping：原样回 pong，便于客户端确认链路存活；其余消息忽略。
        try {
            JsonNode payload = objectMapper.readTree(message.getPayload());
            if ("ping".equals(payload.path("event").asText())) {
                sendJson(session, pongFrame());
            }
        } catch (Exception ignored) {
            // 畸形帧直接忽略，不影响订阅流。
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.debug("agent ws subscriber closed for conversation {} with status {}",
                session.getAttributes().get(AgentStreamAuthHandshakeInterceptor.ATTR_CONVERSATION_ID), status);
    }

    private void stream(WebSocketSession session, Long userId, Long conversationId, long runVersion,
                        ScheduledFuture<?> heartbeat, AtomicLong lastSentAt) {
        try {
            eventService.follow(conversationId, userId, runVersion, 0L, event -> {
                lastSentAt.set(System.currentTimeMillis());
                sendJson(session, eventFrame(event));
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException exception) {
            log.debug("agent ws event stream ended for conversation {}: {}",
                    conversationId, exception.getMessage());
        } finally {
            heartbeat.cancel(false);
            closeQuietly(session, CloseStatus.NORMAL);
        }
    }

    private ScheduledFuture<?> scheduleHeartbeat(WebSocketSession session, AtomicLong lastSentAt) {
        return agentHeartbeatScheduler.scheduleAtFixedRate(() -> {
            if (!session.isOpen()) return;
            // 长工具调用可能长时间无事件，期间仍需保活避免代理/网关断开空闲连接。
            if (System.currentTimeMillis() - lastSentAt.get() >= HEARTBEAT_INTERVAL.toMillis()) {
                try {
                    sendJson(session, pingFrame());
                } catch (RuntimeException ignored) {
                    // 发送失败由流任务统一收尾关闭。
                }
            }
        }, Instant.now().plus(HEARTBEAT_INTERVAL), HEARTBEAT_INTERVAL);
    }

    private ObjectNode eventFrame(AgentEvent event) {
        ObjectNode frame = objectMapper.createObjectNode();
        if (event.getId() != null) frame.put("id", event.getId());
        frame.put("event", event.getEventType());
        Object data = parseData(event.getDataContent());
        if (data instanceof JsonNode node) {
            frame.set("data", node);
        } else if (data != null) {
            frame.put("data", data.toString());
        } else {
            frame.putNull("data");
        }
        return frame;
    }

    /** 事件载荷：能解析为 JSON 的元素保持结构化；纯文本（thought/answer_delta）以 JSON 字符串发送。 */
    private Object parseData(String dataContent) {
        if (dataContent == null) return null;
        try {
            return objectMapper.readTree(dataContent);
        } catch (Exception exception) {
            return dataContent;
        }
    }

    private ObjectNode pingFrame() {
        return objectMapper.createObjectNode().put("event", "ping");
    }

    private ObjectNode pongFrame() {
        return objectMapper.createObjectNode().put("event", "pong");
    }

    private void sendJson(WebSocketSession session, JsonNode frame) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(frame)));
            }
        } catch (Exception exception) {
            throw new IllegalStateException("agent ws send failed", exception);
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) session.close(status);
        } catch (Exception exception) {
            log.debug("agent ws close failed for conversation {}: {}",
                    session.getAttributes().get(AgentStreamAuthHandshakeInterceptor.ATTR_CONVERSATION_ID),
                    exception.getMessage());
        }
    }
}
