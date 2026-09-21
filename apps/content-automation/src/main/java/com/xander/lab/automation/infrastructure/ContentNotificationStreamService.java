package com.xander.lab.automation.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.automation.host.ContentAutomationStreamState;
import com.xander.lab.common.security.ConnectionRegistry;
import com.xander.lab.common.security.SecurityStateCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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

/** Content-owned user notification SSE with local security and connection boundaries. */
@Service
public class ContentNotificationStreamService {

    private static final Logger log = LoggerFactory.getLogger(ContentNotificationStreamService.class);

    private final ObjectMapper objectMapper;
    private final SecurityStateCache securityStateCache;
    private final ConnectionRegistry connectionRegistry;
    private final ContentAutomationStreamState streamState;
    private final Map<Long, List<NotificationConnection>> subscribers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "content-notification-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public ContentNotificationStreamService(ObjectMapper objectMapper,
                                            SecurityStateCache securityStateCache,
                                            ConnectionRegistry connectionRegistry,
                                            ContentAutomationStreamState streamState) {
        this.objectMapper = objectMapper;
        this.securityStateCache = securityStateCache;
        this.connectionRegistry = connectionRegistry;
        this.streamState = streamState;
        heartbeat.scheduleAtFixedRate(this::beatAll, 25, 25, TimeUnit.SECONDS);
    }

    public SseEmitter subscribe(long userId) {
        if (userId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
        if (securityStateCache.isDisabled(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    ConnectionRegistry.REASON_ACCOUNT_DISABLED);
        }
        SseEmitter emitter = new SseEmitter(0L);
        NotificationConnection connection = new NotificationConnection(userId, emitter);
        subscribers.computeIfAbsent(userId, ignored -> new CopyOnWriteArrayList<>()).add(connection);
        connectionRegistry.register(connection);
        streamState.opened();
        emitter.onCompletion(() -> remove(connection));
        emitter.onTimeout(() -> remove(connection));
        emitter.onError(ignored -> remove(connection));
        try {
            emitter.send(SseEmitter.event().name("connected").data("{\"status\":\"connected\"}"));
        } catch (IOException | IllegalStateException exception) {
            remove(connection);
        }
        return emitter;
    }

    /** Pushes a notification to connected tabs; durable persistence is owned by the caller. */
    public void push(Long userId, String type, String title, String message,
                     Long planId, Long runId) {
        if (userId == null) return;
        List<NotificationConnection> connections = subscribers.get(userId);
        if (connections == null || connections.isEmpty()) return;
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "type", type == null ? "NOTIFICATION" : type,
                    "title", title == null ? "" : title,
                    "message", message == null ? "" : message,
                    "planId", planId == null ? "" : planId,
                    "runId", runId == null ? "" : runId));
            for (NotificationConnection connection : connections) {
                emit(connection, SseEmitter.event().name("notification").data(payload));
            }
        } catch (IOException ignored) {
            log.debug("通知 SSE 序列化失败 userId={}", userId);
        }
    }

    private void emit(NotificationConnection connection, SseEmitter.SseEventBuilder event) {
        if (!connection.isOpen()) return;
        if (securityStateCache.isDisabled(connection.accountId())) {
            connection.close(ConnectionRegistry.REASON_ACCOUNT_DISABLED);
            return;
        }
        try {
            connection.emitter.send(event);
        } catch (IOException | IllegalStateException exception) {
            remove(connection);
        }
    }

    private void beatAll() {
        for (List<NotificationConnection> connections : subscribers.values()) {
            for (NotificationConnection connection : connections) {
                emit(connection, SseEmitter.event().comment("heartbeat"));
            }
        }
    }

    private void remove(NotificationConnection connection) {
        if (!connection.open.compareAndSet(true, false)) return;
        connectionRegistry.unregister(connection);
        List<NotificationConnection> connections = subscribers.get(connection.accountId());
        if (connections != null && connections.remove(connection) && connections.isEmpty()) {
            subscribers.remove(connection.accountId(), connections);
        }
        streamState.closed();
        try {
            connection.emitter.complete();
        } catch (IllegalStateException ignored) {
            // The servlet container already completed the response.
        }
    }

    final class NotificationConnection implements ConnectionRegistry.ConnectionHandle {

        private final long userId;
        private final SseEmitter emitter;
        private final AtomicBoolean open = new AtomicBoolean(true);

        private NotificationConnection(long userId, SseEmitter emitter) {
            this.userId = userId;
            this.emitter = emitter;
        }

        private boolean isOpen() {
            return open.get();
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
            if (!open.compareAndSet(true, false)) return;
            connectionRegistry.unregister(this);
            List<NotificationConnection> connections = subscribers.get(userId);
            if (connections != null && connections.remove(this) && connections.isEmpty()) {
                subscribers.remove(userId, connections);
            }
            streamState.closed();
            try {
                if (ConnectionRegistry.REASON_STREAM_MIGRATION.equals(reason)) {
                    emitter.send(SseEmitter.event()
                            .name(ConnectionRegistry.SSE_EVENT_STREAM_MIGRATE)
                            .data("{\"reason\":\"STREAM_MIGRATION\",\"reconnect\":true}"));
                    emitter.complete();
                } else {
                    emitter.completeWithError(new ResponseStatusException(
                            HttpStatus.FORBIDDEN,
                            reason == null ? ConnectionRegistry.REASON_ACCOUNT_DISABLED : reason));
                }
            } catch (IOException | IllegalStateException ignored) {
                try {
                    emitter.complete();
                } catch (IllegalStateException ignoredAgain) {
                    // The servlet container already completed the response.
                }
            }
        }
    }
}
