package com.xander.lab.agent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.agent.application.AgentEventBus;
import com.xander.lab.agent.application.AgentEventService;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentEvent;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
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
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 智能体会话事件流的 WebSocket 订阅端：小程序无法读取 SSE 流，
 * 改用 WS 逐事件推送。推送与落库并行：事件落库后经进程内
 * {@link AgentEventBus} 即时直推本订阅；连接建立时先从数据库重放历史补齐，
 * 依赖事件 id 单调去重衔接两路，不丢不重。
 *
 * <p><b>多实例部署前置条件</b>：AgentEventBus 仅本实例有效。扩容为多实例前，
 * 必须为总线引入跨实例广播（如 Redis pub/sub），否则本订阅收不到其他实例产生的事件。</p>
 * 帧格式与 SSE 事件一致：{"id":事件游标,"event":"answer_delta","data":...}。
 * 一轮终态（complete/error）推送后即关闭连接，下一轮由客户端重新建立。
 */
@Slf4j
public class AgentStreamWebSocketHandler extends TextWebSocketHandler {

    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(20);
    /** 实时等待阶段的防御性检查周期：仅用于发现会话被新代次取代，不是事件获取路径。 */
    static final long SUPERSEDED_CHECK_MILLIS = 5_000L;

    private final AgentEventService eventService;
    private final AgentEventBus eventBus;
    private final ObjectMapper objectMapper;
    private final TaskExecutor agentSseExecutor;
    private final TaskScheduler agentHeartbeatScheduler;
    private final AgentConversationMapper conversationMapper;
    /** 取代检查周期；生产固定值，测试可注入更短周期避免用例等待真实时长。 */
    private final long supersededCheckMillis;

    public AgentStreamWebSocketHandler(AgentEventService eventService,
                                       AgentEventBus eventBus,
                                       ObjectMapper objectMapper,
                                       @Qualifier("agentSseExecutor") TaskExecutor agentSseExecutor,
                                       @Qualifier("agentHeartbeatScheduler") TaskScheduler agentHeartbeatScheduler,
                                       AgentConversationMapper conversationMapper) {
        this(eventService, eventBus, objectMapper, agentSseExecutor, agentHeartbeatScheduler,
                conversationMapper, SUPERSEDED_CHECK_MILLIS);
    }

    /** 测试专用：允许缩短取代检查周期。 */
    AgentStreamWebSocketHandler(AgentEventService eventService,
                                AgentEventBus eventBus,
                                ObjectMapper objectMapper,
                                TaskExecutor agentSseExecutor,
                                TaskScheduler agentHeartbeatScheduler,
                                AgentConversationMapper conversationMapper,
                                long supersededCheckMillis) {
        this.eventService = eventService;
        this.eventBus = eventBus;
        this.objectMapper = objectMapper;
        this.agentSseExecutor = agentSseExecutor;
        this.agentHeartbeatScheduler = agentHeartbeatScheduler;
        this.conversationMapper = conversationMapper;
        this.supersededCheckMillis = supersededCheckMillis;
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
        AtomicLong sentCursor = new AtomicLong(0);
        Object sendLock = new Object();
        java.util.concurrent.CountDownLatch terminal = new java.util.concurrent.CountDownLatch(1);
        // 统一推送出口：按事件 id 单调去重，衔接「历史重放」与「总线实时」两路，不丢不重。
        Consumer<AgentEvent> push = event -> {
            if (event.getRunVersion() != null && event.getRunVersion() != runVersion) return;
            synchronized (sendLock) {
                Long id = event.getId();
                if (id != null && id <= sentCursor.get()) return;
                lastSentAt.set(System.currentTimeMillis());
                sendJson(session, eventFrame(event));
                if (id != null) sentCursor.set(id);
                if ("complete".equals(event.getEventType()) || "error".equals(event.getEventType())) {
                    terminal.countDown();
                }
            }
        };
        // 先挂实时订阅再重放历史：重放期间产生的增量先进总线，由去重出口保证顺序与唯一。
        AgentEventBus.Subscription subscription = eventBus.subscribe(conversationId, push);
        try {
            long cursor = 0L;
            while (terminal.getCount() > 0) {
                List<AgentEvent> batch = eventService.listAfter(conversationId, runVersion, cursor);
                if (batch.isEmpty()) break;
                for (AgentEvent event : batch) {
                    push.accept(event);
                    if (event.getId() != null) cursor = Math.max(cursor, event.getId());
                }
            }
            // 实时阶段：等待终态；周期性防御检查会话是否被新代次取代（非事件获取路径）。
            while (!terminal.await(supersededCheckMillis, TimeUnit.MILLISECONDS)) {
                AgentConversation snapshot = conversationMapper.selectById(conversationId);
                boolean sameRun = snapshot != null
                        && Long.valueOf(runVersion).equals(snapshot.getRunVersion())
                        && userId != null && userId.equals(snapshot.getUserId());
                if (!sameRun || !"running".equals(snapshot.getStatus())) {
                    AgentEvent fallback = eventService.latestTerminal(conversationId, runVersion);
                    if (fallback != null) push.accept(fallback);
                    return;
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException exception) {
            log.debug("agent ws event stream ended for conversation {}: {}",
                    conversationId, exception.getMessage());
        } finally {
            subscription.close();
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
