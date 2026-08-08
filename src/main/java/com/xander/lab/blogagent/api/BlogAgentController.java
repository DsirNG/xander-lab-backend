package com.xander.lab.blogagent.api;

import com.xander.lab.common.Result;
import com.xander.lab.identity.security.UserContext;
import com.xander.lab.blogagent.api.BlogAgentTaskCreateRequest;
import com.xander.lab.blogagent.api.BlogAgentTaskVO;
import com.xander.lab.blogagent.api.BlogAgentMessageRequest;
import com.xander.lab.blogagent.api.BlogAgentSessionVO;
import com.xander.lab.publishing.api.BlogPostVO;
import com.xander.lab.blogagent.domain.BlogAgentEvent;
import com.xander.lab.blogagent.domain.BlogAgentTask;
import com.xander.lab.blogagent.application.BlogAgentEventService;
import com.xander.lab.blogagent.application.BlogAgentService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RestController
@RequestMapping("/api/blog-agent/tasks")
public class BlogAgentController {
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final BlogAgentService service;
    private final BlogAgentEventService eventService;
    private final TaskExecutor blogAgentTaskExecutor;
    private final TaskExecutor blogAgentSseExecutor;
    private final TaskScheduler blogAgentHeartbeatScheduler;

    public BlogAgentController(BlogAgentService service,
                               BlogAgentEventService eventService,
                               @Qualifier("blogAgentTaskExecutor") TaskExecutor blogAgentTaskExecutor,
                               @Qualifier("blogAgentSseExecutor") TaskExecutor blogAgentSseExecutor,
                               @Qualifier("blogAgentHeartbeatScheduler") TaskScheduler blogAgentHeartbeatScheduler) {
        this.service = service;
        this.eventService = eventService;
        this.blogAgentTaskExecutor = blogAgentTaskExecutor;
        this.blogAgentSseExecutor = blogAgentSseExecutor;
        this.blogAgentHeartbeatScheduler = blogAgentHeartbeatScheduler;
    }

    @PostMapping
    public Result<BlogAgentTask> create(@Valid @RequestBody BlogAgentTaskCreateRequest request) {
        return Result.success(service.create(UserContext.getUserId(), request));
    }

    @GetMapping("/{id}")
    public Result<BlogAgentTaskVO> get(@PathVariable Long id) {
        return Result.success(service.get(id, UserContext.getUserId()));
    }

    @GetMapping
    public Result<List<BlogAgentSessionVO>> list() {
        return Result.success(service.listSessions(UserContext.getUserId()));
    }

    @PostMapping("/{id}/run")
    public Result<BlogAgentTaskVO> run(@PathVariable Long id) {
        return Result.success(service.run(id, UserContext.getUserId()));
    }

    @PostMapping("/{id}/publish")
    public Result<BlogPostVO> publish(@PathVariable Long id) {
        return Result.success(service.publish(id, UserContext.getUserId()));
    }

    @PostMapping(value = "/{id}/run/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runStream(@PathVariable Long id, HttpServletResponse response) {
        Long userId = UserContext.getUserId();
        SseEmitter emitter = createEmitter(id, response);
        AtomicBoolean subscriberOpen = new AtomicBoolean(true);
        ScheduledFuture<?> heartbeat = scheduleHeartbeat(id, emitter, subscriberOpen);
        registerSubscriberCallbacks(id, emitter, subscriberOpen, heartbeat);
        CompletableFuture.runAsync(() -> {
            try (BlogAgentEventService.EventSession events = eventService.openSession(
                    id, event -> send(id, emitter, subscriberOpen, event))) {
                try {
                    BlogAgentTaskVO task = service.runStream(id, userId, events::accept);
                    events.accept("complete", task);
                } catch (Exception exception) {
                    events.accept("error", safeMessage(exception));
                }
            } finally {
                heartbeat.cancel(false);
                completeSubscriber(emitter, subscriberOpen);
            }
        }, blogAgentTaskExecutor);
        return emitter;
    }

    @PostMapping(value = "/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter reviseStream(@PathVariable Long id,
                                   @Valid @RequestBody BlogAgentMessageRequest request,
                                   HttpServletResponse response) {
        Long userId = UserContext.getUserId();
        SseEmitter emitter = createEmitter(id, response);
        AtomicBoolean subscriberOpen = new AtomicBoolean(true);
        ScheduledFuture<?> heartbeat = scheduleHeartbeat(id, emitter, subscriberOpen);
        registerSubscriberCallbacks(id, emitter, subscriberOpen, heartbeat);
        CompletableFuture.runAsync(() -> {
            try (BlogAgentEventService.EventSession events = eventService.openSession(
                    id, event -> send(id, emitter, subscriberOpen, event))) {
                try {
                    BlogAgentTaskVO task = service.reviseStream(
                            id, userId, request.getContent(), events::accept);
                    events.accept("complete", task);
                } catch (Exception exception) {
                    events.accept("error", safeMessage(exception));
                }
            } finally {
                heartbeat.cancel(false);
                completeSubscriber(emitter, subscriberOpen);
            }
        }, blogAgentTaskExecutor);
        return emitter;
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@PathVariable Long id,
                                @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                                @RequestParam(value = "afterEventId", required = false) Long afterEventId,
                                HttpServletResponse response) {
        Long userId = UserContext.getUserId();
        service.get(id, userId);
        long cursor = resolveCursor(lastEventId, afterEventId);
        SseEmitter emitter = createEmitter(id, response);
        AtomicBoolean subscriberOpen = new AtomicBoolean(true);
        ScheduledFuture<?> heartbeat = scheduleHeartbeat(id, emitter, subscriberOpen);
        registerSubscriberCallbacks(id, emitter, subscriberOpen, heartbeat);
        CompletableFuture.runAsync(
                () -> followEvents(id, userId, cursor, emitter, subscriberOpen, heartbeat),
                blogAgentSseExecutor);
        return emitter;
    }

    private SseEmitter createEmitter(Long taskId, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        log.debug("blog-agent SSE subscriber opened for task {}", taskId);
        return new SseEmitter(0L);
    }

    ScheduledFuture<?> scheduleHeartbeat(Long taskId, SseEmitter emitter,
                                         AtomicBoolean subscriberOpen) {
        return blogAgentHeartbeatScheduler.scheduleAtFixedRate(
                () -> sendHeartbeat(taskId, emitter, subscriberOpen),
                Instant.now().plus(HEARTBEAT_INTERVAL),
                HEARTBEAT_INTERVAL);
    }

    private void registerSubscriberCallbacks(Long taskId, SseEmitter emitter,
                                             AtomicBoolean subscriberOpen,
                                             ScheduledFuture<?> heartbeat) {
        Runnable close = () -> {
            subscriberOpen.set(false);
            heartbeat.cancel(false);
            log.debug("blog-agent SSE subscriber closed for task {}", taskId);
        };
        emitter.onCompletion(close);
        emitter.onTimeout(close);
        emitter.onError(ignored -> close.run());
    }

    void send(Long taskId, SseEmitter emitter, AtomicBoolean subscriberOpen,
              String event, Object data) {
        send(taskId, emitter, subscriberOpen, null, event, data);
    }

    private void send(Long taskId, SseEmitter emitter, AtomicBoolean subscriberOpen,
                      BlogAgentEvent event) {
        send(taskId, emitter, subscriberOpen, event.getId(),
                event.getEventType(), event.getDataContent());
    }

    private void send(Long taskId, SseEmitter emitter, AtomicBoolean subscriberOpen,
                      Long eventId, String event, Object data) {
        if (!subscriberOpen.get()) return;
        try {
            SseEmitter.SseEventBuilder builder = SseEmitter.event().name(event).data(data);
            if (eventId != null) builder.id(eventId.toString());
            emitter.send(builder);
        } catch (IOException | IllegalStateException ignored) {
            subscriberOpen.set(false);
            log.debug("blog-agent SSE event {} was dropped for closed task subscriber {}", event, taskId);
        }
    }

    private void followEvents(Long taskId, Long userId, long initialCursor,
                              SseEmitter emitter, AtomicBoolean subscriberOpen,
                              ScheduledFuture<?> heartbeat) {
        long cursor = initialCursor;
        try {
            while (subscriberOpen.get()) {
                List<BlogAgentEvent> events = eventService.listAfter(taskId, cursor);
                if (events.isEmpty()) {
                    BlogAgentTaskVO snapshot = service.get(taskId, userId);
                    if (isTerminal(snapshot)) {
                        if ("ready".equals(snapshot.getTask().getStatus())) {
                            send(taskId, emitter, subscriberOpen, "complete", snapshot);
                        } else {
                            send(taskId, emitter, subscriberOpen, "error",
                                    defaultErrorMessage(snapshot.getTask().getErrorMessage()));
                        }
                        return;
                    }
                    Thread.sleep(500);
                    continue;
                }
                for (BlogAgentEvent event : events) {
                    if (event.getId() != null) cursor = event.getId();
                    send(taskId, emitter, subscriberOpen, event);
                    if ("complete".equals(event.getEventType())
                            || "error".equals(event.getEventType())) {
                        return;
                    }
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException exception) {
            send(taskId, emitter, subscriberOpen, "error", safeMessage(exception));
        } finally {
            heartbeat.cancel(false);
            completeSubscriber(emitter, subscriberOpen);
        }
    }

    private boolean isTerminal(BlogAgentTaskVO snapshot) {
        if (snapshot == null || snapshot.getTask() == null) return false;
        String status = snapshot.getTask().getStatus();
        return "ready".equals(status) || "failed".equals(status);
    }

    private long resolveCursor(String lastEventId, Long afterEventId) {
        if (lastEventId != null && !lastEventId.isBlank()) {
            try {
                return Math.max(0L, Long.parseLong(lastEventId));
            } catch (NumberFormatException ignored) {
                log.debug("ignored invalid Last-Event-ID value {}", lastEventId);
            }
        }
        return afterEventId == null ? 0L : Math.max(0L, afterEventId);
    }

    private void sendHeartbeat(Long taskId, SseEmitter emitter, AtomicBoolean subscriberOpen) {
        if (!subscriberOpen.get()) return;
        try {
            emitter.send(SseEmitter.event().comment("heartbeat"));
        } catch (IOException | IllegalStateException ignored) {
            subscriberOpen.set(false);
            log.debug("blog-agent SSE heartbeat was dropped for closed task subscriber {}", taskId);
        }
    }

    private void completeSubscriber(SseEmitter emitter, AtomicBoolean subscriberOpen) {
        if (subscriberOpen.getAndSet(false)) {
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
                // The client or container already completed the response.
            }
        }
    }

    private String safeMessage(Exception exception) {
        if (exception instanceof ResponseStatusException responseStatusException
                && responseStatusException.getReason() != null) {
            return responseStatusException.getReason();
        }
        if (exception instanceof IllegalArgumentException) {
            return exception.getMessage();
        }
        // 其余异常（供应商、基础设施等）只记录日志，不向客户端透传内部细节。
        log.error("blog agent task failed", exception);
        return "博客智能体任务失败，请稍后重试";
    }

    private String defaultErrorMessage(String message) {
        return message == null || message.isBlank() ? "博客智能体任务失败" : message;
    }
}
