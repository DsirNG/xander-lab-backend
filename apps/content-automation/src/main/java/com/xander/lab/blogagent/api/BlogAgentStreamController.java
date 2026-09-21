package com.xander.lab.blogagent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.automation.host.ContentAutomationStreamState;
import com.xander.lab.common.security.ConnectionRegistry;
import com.xander.lab.common.security.SecurityStateCache;
import com.xander.lab.blogagent.application.BlogAgentEventStore;
import com.xander.lab.blogagent.application.BlogAgentTaskReadService;
import com.xander.lab.blogagent.application.BlogAgentTaskWriteService;
import com.xander.lab.blogagent.domain.BlogAgentEvent;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Content-owned BlogAgent SSE endpoints and replay subscription. */
@RestController
@RequestMapping("/api/blog-agent/tasks")
public class BlogAgentStreamController {

    private final BlogAgentTaskWriteService writeService;
    private final BlogAgentTaskReadService readService;
    private final BlogAgentEventStore eventStore;
    private final ObjectMapper objectMapper;
    private final TaskExecutor executor;
    private final ContentAutomationStreamState streamState;
    private final SecurityStateCache securityStateCache;
    private final ConnectionRegistry connectionRegistry;

    public BlogAgentStreamController(
            BlogAgentTaskWriteService writeService,
            BlogAgentTaskReadService readService,
            BlogAgentEventStore eventStore,
            ObjectMapper objectMapper,
            @Qualifier("contentBlogAgentStreamExecutor") TaskExecutor executor,
            ContentAutomationStreamState streamState,
            SecurityStateCache securityStateCache,
            ConnectionRegistry connectionRegistry) {
        this.writeService = writeService;
        this.readService = readService;
        this.eventStore = eventStore;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.streamState = streamState;
        this.securityStateCache = securityStateCache;
        this.connectionRegistry = connectionRegistry;
    }

    @PostMapping(value = "/{id}/run/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @PathVariable long id,
            HttpServletResponse response) {
        long userId = requireUser(userIdHeader);
        readService.detail(userId, id);
        eventStore.clear(id);
        StreamHandle handle = openEmitter(userId, id, response);
        execute(handle, id, () -> writeService.run(userId, id,
                (event, data) -> emit(handle, id, event, data)));
        return handle.emitter;
    }

    @PostMapping(value = "/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter revise(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @PathVariable long id,
            @Valid @RequestBody BlogAgentMessageRequest request,
            HttpServletResponse response) {
        long userId = requireUser(userIdHeader);
        readService.detail(userId, id);
        eventStore.clear(id);
        StreamHandle handle = openEmitter(userId, id, response);
        execute(handle, id, () -> writeService.revise(userId, id, request.getContent(),
                (event, data) -> emit(handle, id, event, data)));
        return handle.emitter;
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @PathVariable long id,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(value = "afterEventId", required = false) Long afterEventId,
            HttpServletResponse response) {
        long userId = requireUser(userIdHeader);
        readService.detail(userId, id);
        long cursor = resolveCursor(lastEventId, afterEventId);
        StreamHandle handle = openEmitter(userId, id, response);
        executor.execute(() -> replay(handle, userId, id, cursor));
        return handle.emitter;
    }

    private void execute(StreamHandle handle, long taskId, StreamWork work) {
        try {
            executor.execute(() -> {
                try {
                    BlogAgentTaskVO task = work.run();
                    emitObject(handle, taskId, "complete", task);
                } catch (Exception exception) {
                    try {
                        emit(handle, taskId, "error", safeMessage(exception));
                    } catch (RuntimeException ignored) {
                        // The client may have disconnected while the task was failing.
                    }
                } finally {
                    handle.complete();
                }
            });
        } catch (RuntimeException exception) {
            handle.complete();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "博客生成助手流式执行队列繁忙", exception);
        }
    }

    private void replay(StreamHandle handle, long userId, long taskId, long cursor) {
        try {
            long lastHeartbeat = System.nanoTime();
            while (handle.isOpen()) {
                List<BlogAgentEvent> events = eventStore.listAfter(taskId, cursor);
                for (BlogAgentEvent event : events) {
                    send(handle, event.getEventType(), event.getDataContent(), event.getId());
                    cursor = event.getId();
                }
                BlogAgentTaskVO snapshot = readService.detail(userId, taskId);
                if (isTerminal(snapshot)) {
                    sendObject(handle, "complete", snapshot);
                    return;
                }
                if (System.nanoTime() - lastHeartbeat >= 15_000_000_000L) {
                    send(handle, "heartbeat", "", null);
                    lastHeartbeat = System.nanoTime();
                }
                Thread.sleep(250L);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            try {
                emit(handle, taskId, "error", safeMessage(exception));
            } catch (RuntimeException ignored) {
                // The client may have disconnected while replaying events.
            }
        } finally {
            handle.complete();
        }
    }

    private StreamHandle openEmitter(long userId, long taskId, HttpServletResponse response) {
        streamState.opened();
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(0L);
        StreamHandle handle = new StreamHandle(userId, taskId, emitter);
        emitter.onCompletion(handle::completeNaturally);
        emitter.onTimeout(handle::completeNaturally);
        emitter.onError(ignored -> handle.completeNaturally());
        connectionRegistry.register(handle);
        return handle;
    }

    private void emit(StreamHandle handle, long taskId, String eventType, String data) {
        long eventId = eventStore.append(taskId, eventType, data);
        send(handle, eventType, data, eventId);
    }

    private void emitObject(StreamHandle handle, long taskId, String eventType, Object data) {
        try {
            emit(handle, taskId, eventType, objectMapper.writeValueAsString(data));
        } catch (Exception exception) {
            throw new IllegalStateException("博客生成助手事件序列化失败", exception);
        }
    }

    private void sendObject(StreamHandle handle, String eventType, Object data) {
        try {
            send(handle, eventType, objectMapper.writeValueAsString(data), null);
        } catch (Exception exception) {
            throw new IllegalStateException("博客生成助手事件序列化失败", exception);
        }
    }

    private void send(StreamHandle handle, String eventType, Object data, Long eventId) {
        if (!handle.isOpen()) return;
        if (securityStateCache.isDisabled(handle.userId)) {
            handle.close(ConnectionRegistry.REASON_ACCOUNT_DISABLED);
            return;
        }
        try {
            SseEmitter.SseEventBuilder builder = SseEmitter.event().name(eventType).data(data);
            if (eventId != null) builder.id(String.valueOf(eventId));
            handle.emitter.send(builder);
        } catch (IOException exception) {
            throw new IllegalStateException("博客生成助手流已断开", exception);
        }
    }

    private static boolean isTerminal(BlogAgentTaskVO snapshot) {
        String status = snapshot.getTask() == null ? null : snapshot.getTask().getStatus();
        return "ready".equals(status) || "failed".equals(status);
    }

    private static long resolveCursor(String lastEventId, Long afterEventId) {
        if (afterEventId != null && afterEventId >= 0) return afterEventId;
        if (!StringUtils.hasText(lastEventId)) return 0L;
        try {
            return Math.max(0L, Long.parseLong(lastEventId));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static long requireUser(String header) {
        try {
            long userId = Long.parseLong(header == null ? "" : header.trim());
            if (userId > 0) return userId;
        } catch (NumberFormatException ignored) {
            // mapped below
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return StringUtils.hasText(message) ? message : "博客生成助手执行失败";
    }

    @FunctionalInterface
    private interface StreamWork {
        BlogAgentTaskVO run();
    }

    private final class StreamHandle implements ConnectionRegistry.ConnectionHandle {

        private final long userId;
        private final long taskId;
        private final SseEmitter emitter;
        private final AtomicBoolean open = new AtomicBoolean(true);

        private StreamHandle(long userId, long taskId, SseEmitter emitter) {
            this.userId = userId;
            this.taskId = taskId;
            this.emitter = emitter;
        }

        private boolean isOpen() {
            return open.get();
        }

        private void completeNaturally() {
            if (open.compareAndSet(true, false)) {
                connectionRegistry.unregister(this);
                streamState.closed();
            }
        }

        private void complete() {
            if (open.compareAndSet(true, false)) {
                connectionRegistry.unregister(this);
                streamState.closed();
                try {
                    emitter.complete();
                } catch (IllegalStateException ignored) {
                    // The client or servlet container already completed the response.
                }
            }
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
                    // The client or servlet container already completed the response.
                }
            }
        }
    }
}
