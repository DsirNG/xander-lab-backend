package com.xander.lab.agent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.agent.application.AgentEventBus;
import com.xander.lab.agent.application.AgentEventService;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentEvent;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import com.xander.lab.common.security.ConnectionRegistry;
import com.xander.lab.common.security.SecurityStateCache;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 智能体会话事件流的 WebSocket 订阅端：小程序无法读取 SSE 流，
 * 改用 WS 逐事件推送。推送与落库由事件生产方编排：普通事件先落库再经进程内
 * {@link AgentEventBus} 广播；流式增量（answer_delta）先经总线直推、再静默落库，
 * 因此用户不会因为一次数据库往返而迟收正文。连接建立时先从数据库重放历史补齐，
 * 依赖事件 id 单调去重衔接两路，不丢不重（无 id 的实时增量不参与去重，故其落库副本必须静默）。
 *
 * <p><b>多实例部署前置条件</b>：AgentEventBus 仅本实例有效。扩容为多实例前，
 * 必须为总线引入跨实例广播（如 Redis pub/sub），否则本订阅收不到其他实例产生的事件。</p>
 *
 * <p><b>封禁（V9 §51）</b>：WS 只在握手阶段鉴权一次，之后整个订阅过程不再校验账号状态。
 * 因此这里同时实现两条手段：手段 A 是把连接登记进
 * {@link ConnectionRegistry}，由每实例的
 * {@link com.xander.lab.identity.application.SecurityStateReconciler} 主动关闭；
 * 手段 B 是 {@link #emit} 在每一帧出站前做 O(1) 校验。命中时以
 * {@code 4403 / ACCOUNT_DISABLED} 关闭，让客户端「停止重连 + 提示已封禁」，
 * 而不是陷入重连风暴。</p>
 *
 * 帧格式与 SSE 事件一致：{"id":事件游标,"event":"answer_delta","data":...}。
 * 一轮终态（complete/error）推送后即关闭连接，下一轮由客户端重新建立。
 */
@Slf4j
public class AgentStreamWebSocketHandler extends TextWebSocketHandler {

    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(20);
    /** 实时等待阶段的防御性检查周期：仅用于发现会话被新代次取代，不是事件获取路径。 */
    static final long SUPERSEDED_CHECK_MILLIS = 5_000L;

    /**
     * 账号被封禁时关闭 WS 连接的应用层 Close Code（V9 §51）。
     *
     * <p>值是 {@link ConnectionRegistry#WS_CLOSE_CODE_ACCOUNT_DISABLED} 的别名：
     * 词汇由共享内核拥有，所有 WS 宿主（当前 agent，Wave 3 之后还有 studio）共用，
     * 避免每个宿主各写一份字面量。</p>
     */
    public static final int CLOSE_CODE_ACCOUNT_DISABLED =
            ConnectionRegistry.WS_CLOSE_CODE_ACCOUNT_DISABLED;
    /** 关闭原因，与 {@link com.xander.lab.identity.application.SecurityStateReconciler} 保持一致。 */
    public static final String CLOSE_REASON_ACCOUNT_DISABLED =
            ConnectionRegistry.REASON_ACCOUNT_DISABLED;

    private final AgentEventService eventService;
    private final AgentEventBus eventBus;
    private final ObjectMapper objectMapper;
    private final TaskExecutor agentSseExecutor;
    private final TaskScheduler agentHeartbeatScheduler;
    private final AgentConversationMapper conversationMapper;
    /** 本实例的已封禁账号集合：每次发送前 O(1) 校验（V9 §51 手段 B）。 */
    private final SecurityStateCache securityStateCache;
    /** 本实例的活跃连接注册表：封禁时主动断开已建立的 WS（V9 §49 / §51 手段 A）。 */
    private final ConnectionRegistry connectionRegistry;
    /** 取代检查周期；生产固定值，测试可注入更短周期避免用例等待真实时长。 */
    private final long supersededCheckMillis;

    public AgentStreamWebSocketHandler(AgentEventService eventService,
                                       AgentEventBus eventBus,
                                       ObjectMapper objectMapper,
                                       @Qualifier("agentSseExecutor") TaskExecutor agentSseExecutor,
                                       @Qualifier("agentHeartbeatScheduler") TaskScheduler agentHeartbeatScheduler,
                                       AgentConversationMapper conversationMapper,
                                       SecurityStateCache securityStateCache,
                                       ConnectionRegistry connectionRegistry) {
        this(eventService, eventBus, objectMapper, agentSseExecutor, agentHeartbeatScheduler,
                conversationMapper, securityStateCache, connectionRegistry, SUPERSEDED_CHECK_MILLIS);
    }

    /** 测试专用：允许缩短取代检查周期。 */
    AgentStreamWebSocketHandler(AgentEventService eventService,
                                AgentEventBus eventBus,
                                ObjectMapper objectMapper,
                                TaskExecutor agentSseExecutor,
                                TaskScheduler agentHeartbeatScheduler,
                                AgentConversationMapper conversationMapper,
                                long supersededCheckMillis) {
        this(eventService, eventBus, objectMapper, agentSseExecutor, agentHeartbeatScheduler,
                conversationMapper, new SecurityStateCache(), new ConnectionRegistry(),
                supersededCheckMillis);
    }

    /**
     * 测试专用：注入共享的安全状态缓存与连接注册表，用于验证封禁路径。
     *
     * <p>生产代码走 {@link #AgentStreamWebSocketHandler(AgentEventService, AgentEventBus,
     * ObjectMapper, TaskExecutor, TaskScheduler, AgentConversationMapper, SecurityStateCache,
     * ConnectionRegistry)}；这里的重载让用例可以同时持有 cache 与 registry 的引用，
     * 从而断言「封禁 → 4403 关闭 → 注册表摘除」这条链路。</p>
     */
    AgentStreamWebSocketHandler(AgentEventService eventService,
                                AgentEventBus eventBus,
                                ObjectMapper objectMapper,
                                TaskExecutor agentSseExecutor,
                                TaskScheduler agentHeartbeatScheduler,
                                AgentConversationMapper conversationMapper,
                                SecurityStateCache securityStateCache,
                                ConnectionRegistry connectionRegistry,
                                long supersededCheckMillis) {
        this.eventService = eventService;
        this.eventBus = eventBus;
        this.objectMapper = objectMapper;
        this.agentSseExecutor = agentSseExecutor;
        this.agentHeartbeatScheduler = agentHeartbeatScheduler;
        this.conversationMapper = conversationMapper;
        this.securityStateCache = securityStateCache;
        this.connectionRegistry = connectionRegistry;
        this.supersededCheckMillis = supersededCheckMillis;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get(AgentStreamAuthHandshakeInterceptor.ATTR_USER_ID);
        Long conversationId = (Long) session.getAttributes().get(AgentStreamAuthHandshakeInterceptor.ATTR_CONVERSATION_ID);
        Long runVersion = (Long) session.getAttributes().get(AgentStreamAuthHandshakeInterceptor.ATTR_RUN_VERSION);
        if (userId == null || conversationId == null || runVersion == null) {
            log.warn("agent ws connection rejected after handshake conversation={} run={} reason=missing-session-attributes",
                    conversationId, runVersion);
            closeQuietly(session, CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        // 建连即校验一次封禁状态：封禁账号不应还能建立新的订阅。
        // 这是 §51 手段 B 的入口侧；手段 A（reconciler 主动断开）负责已建立的连接。
        if (securityStateCache.isDisabled(userId)) {
            log.info("[Security] 账号已封禁，拒绝建立 WS 订阅 account={} conversation={} run={}",
                    userId, conversationId, runVersion);
            closeQuietly(session, accountDisabledStatus());
            return;
        }
        log.info("agent ws subscriber connected conversation={} run={}", conversationId, runVersion);
        WsConnectionHandle handle =
                new WsConnectionHandle(session, userId, conversationId, runVersion);
        connectionRegistry.register(handle);
        ScheduledFuture<?> heartbeat = scheduleHeartbeat(handle);
        try {
            agentSseExecutor.execute(() -> stream(handle, heartbeat));
        } catch (RuntimeException exception) {
            log.warn("agent ws subscriber scheduling failed conversation={} run={} errorType={}",
                    conversationId, runVersion, exception.getClass().getSimpleName());
            heartbeat.cancel(false);
            handle.markClosed();
            closeQuietly(session, CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 客户端保活 ping：原样回 pong，便于客户端确认链路存活；其余消息忽略。
        try {
            JsonNode payload = objectMapper.readTree(message.getPayload());
            if ("ping".equals(payload.path("event").asText())) {
                Long accountId = (Long) session.getAttributes()
                        .get(AgentStreamAuthHandshakeInterceptor.ATTR_USER_ID);
                if (securityStateCache.isDisabled(accountId)) {
                    // 封禁账号的保活 ping 不再回 pong：直接按 4403 关闭，
                    // 否则客户端会误判链路健康、继续重连。
                    log.info("[Security] 账号已封禁，拒绝 WS 保活 account={}", accountId);
                    closeQuietly(session, accountDisabledStatus());
                    return;
                }
                sendJson(session, pongFrame());
            }
        } catch (Exception ignored) {
            // 畸形帧直接忽略，不影响订阅流。
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("agent ws subscriber closed conversation={} run={} closeCode={} reason={}",
                session.getAttributes().get(AgentStreamAuthHandshakeInterceptor.ATTR_CONVERSATION_ID),
                session.getAttributes().get(AgentStreamAuthHandshakeInterceptor.ATTR_RUN_VERSION),
                status.getCode(), status.getReason() == null ? "-" : status.getReason());
    }

    private void stream(WsConnectionHandle handle, ScheduledFuture<?> heartbeat) {
        WebSocketSession session = handle.session;
        Long userId = handle.accountId;
        long conversationId = handle.conversationId;
        long runVersion = handle.runVersion;
        AtomicLong sentCursor = new AtomicLong(0);
        AtomicBoolean firstEventSent = new AtomicBoolean(false);
        Object sendLock = new Object();
        java.util.concurrent.CountDownLatch terminal = new java.util.concurrent.CountDownLatch(1);
        // 统一推送出口：按事件 id 单调去重，衔接「历史重放」与「总线实时」两路，不丢不重。
        Consumer<AgentEvent> push = event -> {
            if (event.getRunVersion() != null && event.getRunVersion() != runVersion) return;
            synchronized (sendLock) {
                Long id = event.getId();
                if (id != null && id <= sentCursor.get()) return;
                if (!emit(handle, eventFrame(event))) {
                    // 已封禁并关闭连接：让等待循环立即退出，不再等终态。
                    terminal.countDown();
                    return;
                }
                handle.lastSentAt.set(System.currentTimeMillis());
                if (firstEventSent.compareAndSet(false, true)) {
                    log.info("agent ws first event sent conversation={} run={} event={} eventId={}",
                            conversationId, runVersion, event.getEventType(), event.getId());
                }
                if (id != null) sentCursor.set(id);
                if ("complete".equals(event.getEventType()) || "error".equals(event.getEventType())) {
                    log.info("agent ws terminal event sent conversation={} run={} event={} eventId={}",
                            conversationId, runVersion, event.getEventType(), event.getId());
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
                // 连接已被封禁关闭或客户端已断开：立即收尾，避免空转等待终态。
                // （V9 §51：封禁后必须在 SLO 内停止推送，而不是等本轮跑完。）
                if (!handle.isOpen()) return;
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
            handle.markClosed();
            closeQuietly(session, CloseStatus.NORMAL);
        }
    }

    private ScheduledFuture<?> scheduleHeartbeat(WsConnectionHandle handle) {
        return agentHeartbeatScheduler.scheduleAtFixedRate(() -> {
            if (!handle.isOpen() || !handle.session.isOpen()) return;
            // 封禁校验放在间隔判断之前：即使刚刚有事件、保活间隔未到，也能在下一次
            // tick 断开（V9 §28.4：心跳只是兜底，主要手段是 reconciler 的主动断开；
            // 若要把心跳当主要手段，interval 必须 <= 2 秒并写进 contract-changes.yaml）。
            if (securityStateCache.isDisabled(handle.accountId)) {
                log.info("[Security] 账号已封禁，心跳终止 WS account={} conversation={}",
                        handle.accountId, handle.conversationId);
                handle.close(CLOSE_REASON_ACCOUNT_DISABLED);
                return;
            }
            // 长工具调用可能长时间无事件，期间仍需保活避免代理/网关断开空闲连接。
            if (System.currentTimeMillis() - handle.lastSentAt.get() >= HEARTBEAT_INTERVAL.toMillis()) {
                try {
                    emit(handle, pingFrame());
                } catch (RuntimeException ignored) {
                    // 发送失败由流任务统一收尾关闭。
                }
            }
        }, Instant.now().plus(HEARTBEAT_INTERVAL), HEARTBEAT_INTERVAL);
    }

    /**
     * 统一推送出口（V9 §51 手段 B）：任何一帧出站前都校验封禁状态。
     *
     * <p>为什么必须放在出口而不是只在建连时校验：WS 只在握手阶段鉴权一次，
     * {@code afterConnectionEstablished} 把 userId 取进局部变量之后，
     * 整个订阅过程不再做任何账号状态校验。若不主动终止已建立的连接，
     * 被封禁的账号可以继续接收事件直到本轮自然结束。</p>
     *
     * @return 是否成功发出；{@code false} 表示已封禁并关闭连接，调用方应立即停止推送
     */
    private boolean emit(WsConnectionHandle handle, JsonNode frame) {
        if (!handle.isOpen()) return false;
        if (securityStateCache.isDisabled(handle.accountId)) {
            log.info("[Security] 账号已封禁，关闭 WS 订阅 account={} conversation={} run={}",
                    handle.accountId, handle.conversationId, handle.runVersion);
            handle.close(CLOSE_REASON_ACCOUNT_DISABLED);
            return false;
        }
        sendJson(handle.session, frame);
        return true;
    }

    private static CloseStatus accountDisabledStatus() {
        return new CloseStatus(CLOSE_CODE_ACCOUNT_DISABLED, CLOSE_REASON_ACCOUNT_DISABLED);
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

    /**
     * 一条 WS 订阅的连接句柄（V9 §49 / §51）。
     *
     * <p>把「会话、账号、会话 id、代次、最近发送时间、开闭标志」打成一个对象：
     * 既让发送出口能一次拿到全部上下文，也让封禁时可以从 {@link ConnectionRegistry}
     * 反查到需要关闭的连接。</p>
     *
     * <p>{@link #close(String)} 是封禁路径的入口，由
     * {@link com.xander.lab.identity.application.SecurityStateReconciler} 通过
     * {@code connectionRegistry.closeAll(accountId, reason)} 触发，
     * 把连接以 {@code 4403 / ACCOUNT_DISABLED} 关闭。</p>
     */
    final class WsConnectionHandle implements ConnectionRegistry.ConnectionHandle {
        private final WebSocketSession session;
        private final Long accountId;
        private final long conversationId;
        private final long runVersion;
        /** 最近一次成功出站的时间戳，供心跳判断是否需要保活。 */
        private final AtomicLong lastSentAt = new AtomicLong(System.currentTimeMillis());
        private final AtomicBoolean open = new AtomicBoolean(true);

        WsConnectionHandle(WebSocketSession session, Long accountId, long conversationId,
                           long runVersion) {
            this.session = session;
            this.accountId = accountId;
            this.conversationId = conversationId;
            this.runVersion = runVersion;
        }

        @Override
        public Long accountId() {
            return accountId;
        }

        @Override
        public String kind() {
            return ConnectionRegistry.KIND_WS;
        }

        /**
         * 封禁路径（V9 §51 手段 A）：以 4403 / ACCOUNT_DISABLED 关闭连接。
         *
         * <p>幂等：重复调用只生效一次。关闭后会把自己从注册表摘除，
         * 因为 {@code closeAll} 已经按账号整体移除过，这里的摘除是为了
         * 覆盖「先被心跳/出口发现封禁、随后 reconciler 才扫到」的路径。</p>
         */
        @Override
        public void close(String reason) {
            if (!open.getAndSet(false)) return;
            connectionRegistry.unregister(this);
            closeQuietly(session, new CloseStatus(CLOSE_CODE_ACCOUNT_DISABLED,
                    reason == null ? CLOSE_REASON_ACCOUNT_DISABLED : reason));
        }

        /** 连接自然结束（终态、客户端断开、发送失败）时摘除自己。 */
        void markClosed() {
            open.set(false);
            connectionRegistry.unregister(this);
        }

        /** 连接是否仍然可用。封禁关闭或自然收尾后为 false。 */
        boolean isOpen() {
            return open.get();
        }
    }
}
