package com.xander.lab.automation.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.automation.spi.NotificationBroadcastPort;
import com.xander.lab.common.security.ConnectionRegistry;
import com.xander.lab.common.security.SecurityStateCache;
import com.xander.lab.core.stream.CoreHostStreamProperties;
import com.xander.lab.core.stream.CoreLegacyStreamDrainRegistry;
import com.xander.lab.core.stream.CoreLegacyStreamState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-level notification stream. Each logged-in tab subscribes once to
 * {@code GET /api/notifications/events}; background plan runs push through the
 * in-memory registry below. The blog_notification table remains the durable
 * fallback for offline users, so this stream is loss-tolerant by design.
 *
 * <p><b>封禁</b>：本流用 {@code new SseEmitter(0L)} 永不超时，
 * 建连之后不再有任何账号状态校验。所以这里同样实现两条手段：
 * 手段 A 是把每条连接登记进 {@link ConnectionRegistry}，由每实例的 reconciler 主动关闭；
 * 手段 B 是 {@link #emit} 在每一帧出站前（含心跳）做 O(1) 校验。
 * 命中时以 403 结束该条连接，而不是静默丢弃事件 —— 静默丢弃会让前端一直显示"在线"。</p>
 *
 * <p><b>跨域面</b>：本类只对外暴露<b>一个</b>契约方法
 * {@link #broadcast}（{@link NotificationBroadcastPort}）。{@link #subscribe} 返回
 * {@code SseEmitter}（框架类型），{@link #push} 收 {@code planId} / {@code runId}
 * （blog-plan 域的概念），连接注册表与封禁校验都是实现细节 —— 它们全部留在本类里。
 * {@code admin} 域此前直接注入本类、只为调一个 {@code broadcast}；现在改注入端口，
 * 于是它不再需要知道 {@code SseEmitter} 存在过。</p>
 */
@Slf4j
@Component
public class BlogPlanSseService implements NotificationBroadcastPort {

    private final ObjectMapper objectMapper;
    /** 本实例的已封禁账号集合。 */
    private final SecurityStateCache securityStateCache;
    /** 本实例的活跃连接注册表。 */
    private final ConnectionRegistry connectionRegistry;
    private final CoreHostStreamProperties streamProperties;
    private final CoreLegacyStreamDrainRegistry legacyStreamDrainRegistry;
    private final CoreLegacyStreamState streamState;
    private final Map<Long, List<NotificationConnection>> subscribers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "blog-plan-sse-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    @Autowired
    public BlogPlanSseService(ObjectMapper objectMapper,
                              SecurityStateCache securityStateCache,
                              ConnectionRegistry connectionRegistry,
                              CoreHostStreamProperties streamProperties,
                              CoreLegacyStreamDrainRegistry legacyStreamDrainRegistry,
                              CoreLegacyStreamState streamState) {
        this.objectMapper = objectMapper;
        this.securityStateCache = securityStateCache;
        this.connectionRegistry = connectionRegistry;
        this.streamProperties = streamProperties;
        this.legacyStreamDrainRegistry = legacyStreamDrainRegistry;
        this.streamState = streamState;
        this.heartbeat.scheduleAtFixedRate(this::beatAll, 25, 25, TimeUnit.SECONDS);
    }

    /**
     * 测试专用：使用隔离的安全平面。
     *
     * <p>每个用例各持一份 cache / registry，避免封禁状态在用例之间泄漏
     * （这类泄漏表现为"单独跑绿、全量跑红"）。</p>
     */
    BlogPlanSseService(ObjectMapper objectMapper) {
        this(objectMapper, new SecurityStateCache(), new ConnectionRegistry(),
                new CoreHostStreamProperties(), new CoreLegacyStreamDrainRegistry(), new CoreLegacyStreamState());
    }

    BlogPlanSseService(ObjectMapper objectMapper,
                       SecurityStateCache securityStateCache,
                       ConnectionRegistry connectionRegistry,
                       CoreHostStreamProperties streamProperties,
                       CoreLegacyStreamState streamState) {
        this(objectMapper, securityStateCache, connectionRegistry, streamProperties,
                new CoreLegacyStreamDrainRegistry(), streamState);
    }

    /** Registers one connection and primes callbacks so per-user lists never
     * retain completed emitters. */
    public SseEmitter subscribe(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("SSE 连接需要已登录用户");
        }
        if (streamProperties.isDrainEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "旧通知流正在切换，请稍后重试");
        }
        // 入口侧校验：封禁账号不应还能建立新的通知订阅。
        // 这里抛 403 而不是返回一个立刻关闭的 emitter —— 后者会让客户端先收到 200 再断开，
        // 看起来像网络抖动，从而触发无意义的重连。
        if (securityStateCache.isDisabled(userId)) {
            log.info("[Security] 账号已封禁，拒绝建立通知 SSE userId={}", userId);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    ConnectionRegistry.REASON_ACCOUNT_DISABLED);
        }
        SseEmitter emitter = new SseEmitter(0L);
        NotificationConnection connection = new NotificationConnection(userId, emitter);
        List<NotificationConnection> connections =
                subscribers.computeIfAbsent(userId, key -> new CopyOnWriteArrayList<>());
        connections.add(connection);
        connectionRegistry.register(connection);
        legacyStreamDrainRegistry.register(connection);
        streamState.openedLegacyContentSse();
        emitter.onCompletion(() -> remove(userId, connection));
        emitter.onTimeout(() -> remove(userId, connection));
        emitter.onError(error -> remove(userId, connection));
        try {
            // Flush headers immediately and give clients an explicit ready signal.
            emitter.send(SseEmitter.event().name("connected").data("{\"status\":\"connected\"}"));
            log.info("[SSE] 已连接 userId={}, activeConnections={}", userId, connections.size());
        } catch (IOException | IllegalStateException exception) {
            log.warn("[SSE] 建立连接失败 userId={}: {}", userId, exception.getMessage());
            remove(userId, connection);
        }
        return emitter;
    }

    /** Pushes a live event to every connected tab of the user. Never throws. */
    public void push(Long userId, String type, String title, String message,
                     Long planId, Long runId) {
        if (userId == null) return;
        List<NotificationConnection> connections = subscribers.get(userId);
        if (connections == null || connections.isEmpty()) return;
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    "type", type == null ? "NOTIFICATION" : type,
                    "title", title == null ? "" : title,
                    "message", message == null ? "" : message,
                    "planId", planId == null ? "" : planId,
                    "runId", runId == null ? "" : runId));
        } catch (IOException ignored) {
            return;
        }
        for (NotificationConnection connection : connections) {
            emit(connection, SseEmitter.event().name("notification").data(payload));
        }
    }

    /**
     * Broadcasts a live-only diagnostic message and returns its recipient count.
     *
     * <p>{@inheritDoc}</p>
     *
     * <p>⚠️ 这里<b>不再收</b> {@code planId} / {@code runId}。它们是 blog-plan 域
     * 的概念，而唯一调用方 {@code admin} 的诊断端点永远传 {@code null} —— 一个要求调用方
     * 每次都撒谎的参数不该出现在契约上。收窄可证是行为保持的：转发给 {@link #push} 的两个值
     * 由"调用方传 null"变成"这里写死 null"，而 {@code push} 本来就把 null 序列化成空串，
     * 所以线上载荷逐字节不变（{@code BlogPlanSseServiceTest} 有对应断言）。</p>
     */
    @Override
    public int broadcast(String type, String title, String message) {
        int recipients = 0;
        for (Long userId : subscribers.keySet()) {
            List<NotificationConnection> connections = subscribers.get(userId);
            if (connections != null) recipients += connections.size();
            push(userId, type, title, message, null, null);
        }
        log.info("[SSE] 广播测试已发送 activeConnections={}", recipients);
        return recipients;
    }

    /**
     * 统一推送出口：任何一帧出站前都校验封禁状态。
     *
     * <p>心跳也走这里 —— 通知流可能长时间没有业务事件，如果只在业务事件上校验，
     * 被封禁的标签页会一直保持"已连接"，直到用户主动刷新。</p>
     */
    private void emit(NotificationConnection connection, SseEmitter.SseEventBuilder builder) {
        if (!connection.isOpen()) return;
        if (securityStateCache.isDisabled(connection.accountId())) {
            log.info("[Security] 账号已封禁，关闭通知 SSE userId={}", connection.accountId());
            connection.close(ConnectionRegistry.REASON_ACCOUNT_DISABLED);
            return;
        }
        try {
            connection.emitter.send(builder);
        } catch (IOException | IllegalStateException dropped) {
            remove(connection.accountId(), connection);
        }
    }

    private void beatAll() {
        for (Map.Entry<Long, List<NotificationConnection>> entry : subscribers.entrySet()) {
            for (NotificationConnection connection : entry.getValue()) {
                emit(connection, SseEmitter.event().comment("heartbeat"));
            }
        }
    }

    /**
     * Unregisters one connection. Idempotent.
     *
     * <p>每个 emitter 的 onCompletion / onTimeout / onError 都会走到这里，心跳线程在
     * 发送失败时也会调一次，所以同一个 emitter 天然会被通知多次。早先没有幂等判断，
     * 结果一次断连会打出 2~6 行 "已断开"（多 tab 时更明显），日志里全是噪声，
     * 也让人误以为连接在反复抖动。这里以"是否真的从列表里摘掉了它"为准：
     * 摘不到的调用是重复通知，直接返回。
     */
    private void remove(Long userId, NotificationConnection connection) {
        boolean firstClose = connection.markClosed();
        connectionRegistry.unregister(connection);
        legacyStreamDrainRegistry.unregister(connection);
        if (firstClose) {
            streamState.closedLegacyContentSse();
        }
        List<NotificationConnection> connections = subscribers.get(userId);
        if (connections == null || !connections.remove(connection)) {
            return;
        }
        if (connections.isEmpty()) {
            subscribers.remove(userId, connections);
        }
        try {
            connection.emitter.complete();
        } catch (IllegalStateException ignored) {
            // Connection already closed.
        }
        log.info("[SSE] 已断开 userId={}", userId);
    }

    /**
     * 一条通知流的连接句柄。
     *
     * <p>{@link #close(String)} 是封禁路径入口，由 reconciler 通过
     * {@code connectionRegistry.closeAll(accountId, reason)} 触发。用
     * {@code completeWithError(403)} 而不是 {@code complete()}：前者让浏览器
     * 的 EventSource 触发 error 事件，前端才能区分"正常收尾"与"被踢下线"。</p>
     */
    final class NotificationConnection implements ConnectionRegistry.ConnectionHandle {
        private final Long userId;
        private final SseEmitter emitter;
        private final AtomicBoolean open = new AtomicBoolean(true);

        NotificationConnection(Long userId, SseEmitter emitter) {
            this.userId = userId;
            this.emitter = emitter;
        }

        @Override
        public Long accountId() {
            return userId;
        }

        @Override
        public String kind() {
            return ConnectionRegistry.KIND_SSE;
        }

        @Override
        public void close(String reason) {
            if (ConnectionRegistry.REASON_STREAM_MIGRATION.equals(reason)) {
                if (!open.get()) return;
                try {
                    emitter.send(SseEmitter.event()
                            .name(ConnectionRegistry.SSE_EVENT_STREAM_MIGRATE)
                            .data("{\"reason\":\"STREAM_MIGRATION\",\"reconnect\":true}"));
                } catch (IOException | IllegalStateException ignored) {
                    // The client may have disconnected while the migration signal was sent.
                }
                remove(userId, this);
                return;
            }
            if (!open.getAndSet(false)) return;
            connectionRegistry.unregister(this);
            legacyStreamDrainRegistry.unregister(this);
            streamState.closedLegacyContentSse();
            remove(userId, this);
            try {
                emitter.completeWithError(new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        reason == null ? ConnectionRegistry.REASON_ACCOUNT_DISABLED : reason));
            } catch (IllegalStateException ignored) {
                try {
                    emitter.complete();
                } catch (IllegalStateException ignoredAgain) {
                    // Connection already closed.
                }
            }
        }

        boolean markClosed() {
            return open.getAndSet(false);
        }

        boolean isOpen() {
            return open.get();
        }
    }
}
