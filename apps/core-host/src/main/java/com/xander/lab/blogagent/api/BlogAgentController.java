package com.xander.lab.blogagent.api;

import com.xander.lab.common.Result;
import com.xander.lab.common.security.ConnectionRegistry;
import com.xander.lab.common.security.SecurityStateCache;
import com.xander.lab.auth.core.UserContext;
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
import org.springframework.http.HttpStatus;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 博客生成助手的 REST + SSE 门面。
 *
 * <p><b>封禁（V9 §50 / §51）</b>：三条 SSE 端点都用 {@code new SseEmitter(0L)} 永不超时，
 * 建连之后不再做任何账号状态校验。所以每条连接都登记进 {@link ConnectionRegistry}
 * （手段 A：reconciler 主动断开），且 {@link #emit} 在每一帧出站前（含心跳）做
 * O(1) 校验（手段 B：兜底）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/blog-agent/tasks")
public class BlogAgentController {
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    private static final String TASK_QUEUE_BUSY_MESSAGE = "博客生成助手执行队列繁忙，请稍后重试";
    private static final String SUBSCRIPTION_QUEUE_BUSY_MESSAGE = "博客生成助手事件订阅繁忙，请稍后重试";

    private final BlogAgentService service;
    private final BlogAgentEventService eventService;
    private final TaskExecutor blogAgentTaskExecutor;
    private final TaskExecutor blogAgentSseExecutor;
    private final TaskScheduler blogAgentHeartbeatScheduler;
    /** 本实例的已封禁账号集合：每次发送前 O(1) 校验（V9 §50 手段 B）。 */
    private final SecurityStateCache securityStateCache;
    /** 本实例的活跃连接注册表：封禁时主动断开已建立的流（V9 §49 手段 A）。 */
    private final ConnectionRegistry connectionRegistry;

    public BlogAgentController(BlogAgentService service,
                               BlogAgentEventService eventService,
                               @Qualifier("blogAgentTaskExecutor") TaskExecutor blogAgentTaskExecutor,
                               @Qualifier("blogAgentSseExecutor") TaskExecutor blogAgentSseExecutor,
                               @Qualifier("blogAgentHeartbeatScheduler") TaskScheduler blogAgentHeartbeatScheduler,
                               SecurityStateCache securityStateCache,
                               ConnectionRegistry connectionRegistry) {
        this.service = service;
        this.eventService = eventService;
        this.blogAgentTaskExecutor = blogAgentTaskExecutor;
        this.blogAgentSseExecutor = blogAgentSseExecutor;
        this.blogAgentHeartbeatScheduler = blogAgentHeartbeatScheduler;
        this.securityStateCache = securityStateCache;
        this.connectionRegistry = connectionRegistry;
    }

    /**
     * Compatibility constructor for focused controller tests.
     *
     * <p>使用隔离的安全平面：封禁路径在这些用例里是惰性的，
     * 专门验证封禁的用例应显式构造 cache / registry。</p>
     */
    BlogAgentController(BlogAgentService service,
                        BlogAgentEventService eventService,
                        TaskExecutor blogAgentTaskExecutor,
                        TaskExecutor blogAgentSseExecutor,
                        TaskScheduler blogAgentHeartbeatScheduler) {
        this(service, eventService, blogAgentTaskExecutor, blogAgentSseExecutor,
                blogAgentHeartbeatScheduler, new SecurityStateCache(), new ConnectionRegistry());
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
        // ⚠️ 必须点名 publishForView：四参以外的 publish(Long, Long) 现在是端口方法，
        // 参数是 (userId, taskId)。写回 publish(id, userId) 会编译通过但静默换位。
        return Result.success(service.publishForView(id, UserContext.getUserId()));
    }

    @PostMapping(value = "/{id}/run/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runStream(@PathVariable Long id, HttpServletResponse response) {
        Long userId = UserContext.getUserId();
        return submitTaskStream(
                id,
                userId,
                response,
                events -> {
                    BlogAgentTaskVO task = service.runStream(id, userId, events::accept);
                    events.accept("complete", task);
                },
                message -> service.failQueuedInitialRun(id, userId, message));
    }

    @PostMapping(value = "/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter reviseStream(@PathVariable Long id,
                                   @Valid @RequestBody BlogAgentMessageRequest request,
                                   HttpServletResponse response) {
        Long userId = UserContext.getUserId();
        return submitTaskStream(
                id,
                userId,
                response,
                events -> {
                    BlogAgentTaskVO task = service.reviseStream(
                            id, userId, request.getContent(), events::accept);
                    events.accept("complete", task);
                },
                message -> service.failQueuedRevision(id, userId, message));
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
        SseSubscriber subscriber = new SseSubscriber(connectionRegistry, id, userId, emitter);
        ScheduledFuture<?> heartbeat;
        try {
            heartbeat = scheduleHeartbeat(subscriber);
        } catch (RejectedExecutionException exception) {
            completeSubscriber(subscriber);
            throw subscriptionUnavailable(exception);
        }
        registerSubscriberCallbacks(subscriber, heartbeat);
        connectionRegistry.register(subscriber);
        try {
            CompletableFuture.runAsync(
                    () -> followEvents(id, userId, cursor, subscriber),
                    blogAgentSseExecutor);
        } catch (RejectedExecutionException exception) {
            closeSubscriber(subscriber);
            throw subscriptionUnavailable(exception);
        }
        return emitter;
    }

    SseEmitter submitTaskStream(Long taskId,
                                Long userId,
                                HttpServletResponse response,
                                StreamWork work,
                                Consumer<String> failQueued) {
        SseEmitter emitter = createEmitter(taskId, response);
        SseSubscriber subscriber = new SseSubscriber(connectionRegistry, taskId, userId, emitter);
        ScheduledFuture<?> heartbeat;
        try {
            heartbeat = scheduleHeartbeat(subscriber);
        } catch (RejectedExecutionException exception) {
            failRejectedTaskStream(subscriber, null, failQueued, exception);
            return emitter;
        }
        registerSubscriberCallbacks(subscriber, heartbeat);
        connectionRegistry.register(subscriber);
        try {
            CompletableFuture.runAsync(() -> {
                try (BlogAgentEventService.EventSession events = eventService.openSession(
                        taskId, event -> send(subscriber, event))) {
                    try {
                        work.execute(events);
                    } catch (Exception exception) {
                        events.accept("error", safeMessage(exception));
                    }
                } finally {
                    closeSubscriber(subscriber);
                }
            }, blogAgentTaskExecutor);
        } catch (RejectedExecutionException exception) {
            failRejectedTaskStream(subscriber, heartbeat, failQueued, exception);
        }
        return emitter;
    }

    private void failRejectedTaskStream(SseSubscriber subscriber,
                                        ScheduledFuture<?> heartbeat,
                                        Consumer<String> failQueued,
                                        RejectedExecutionException rejection) {
        if (heartbeat != null) heartbeat.cancel(false);
        try {
            failQueued.accept(TASK_QUEUE_BUSY_MESSAGE);
        } catch (RuntimeException exception) {
            log.error("blog-agent rejected task {} could not be marked failed",
                    subscriber.taskId, exception);
        }
        try (BlogAgentEventService.EventSession events = eventService.openSession(
                subscriber.taskId, event -> send(subscriber, event))) {
            events.accept("error", TASK_QUEUE_BUSY_MESSAGE);
        } catch (RuntimeException exception) {
            log.error("blog-agent rejected task {} error event could not be persisted",
                    subscriber.taskId, exception);
        } finally {
            completeSubscriber(subscriber);
        }
        log.warn("blog-agent task executor rejected task {}", subscriber.taskId, rejection);
    }

    private void closeSubscriber(SseSubscriber subscriber) {
        subscriber.heartbeatCancel();
        completeSubscriber(subscriber);
    }

    private ResponseStatusException subscriptionUnavailable(RejectedExecutionException cause) {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                SUBSCRIPTION_QUEUE_BUSY_MESSAGE,
                cause);
    }

    @FunctionalInterface
    interface StreamWork {
        void execute(BlogAgentEventService.EventSession events);
    }

    SseEmitter createEmitter(Long taskId, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        log.debug("blog-agent SSE subscriber opened for task {}", taskId);
        return new SseEmitter(0L);
    }

    ScheduledFuture<?> scheduleHeartbeat(SseSubscriber subscriber) {
        ScheduledFuture<?> future = blogAgentHeartbeatScheduler.scheduleAtFixedRate(
                () -> sendHeartbeat(subscriber),
                Instant.now().plus(HEARTBEAT_INTERVAL),
                HEARTBEAT_INTERVAL);
        subscriber.heartbeat = future;
        return future;
    }

    private void registerSubscriberCallbacks(SseSubscriber subscriber,
                                             ScheduledFuture<?> heartbeat) {
        Runnable close = () -> {
            subscriber.subscriberOpen.set(false);
            heartbeat.cancel(false);
            connectionRegistry.unregister(subscriber);
            log.debug("blog-agent SSE subscriber closed for task {}", subscriber.taskId);
        };
        subscriber.emitter.onCompletion(close);
        subscriber.emitter.onTimeout(close);
        subscriber.emitter.onError(ignored -> close.run());
    }

    void send(SseSubscriber subscriber, String event, Object data) {
        send(subscriber, null, event, data);
    }

    private void send(SseSubscriber subscriber, BlogAgentEvent event) {
        send(subscriber, event.getId(), event.getEventType(), event.getDataContent());
    }

    /**
     * 统一推送出口（V9 §50 手段 B）：任何一帧出站前都校验封禁状态。
     *
     * <p>心跳也走这里 —— 长工具调用期间可能长时间无业务事件，若只在业务事件上校验，
     * 被封禁的标签页会一直保持"生成中"，直到用户主动刷新。</p>
     */
    private void send(SseSubscriber subscriber, Long eventId, String event, Object data) {
        if (!subscriber.isOpen()) return;
        if (securityStateCache.isDisabled(subscriber.userId)) {
            log.info("[Security] 账号已封禁，终止已建立的 SSE 推送 account={} task={}",
                    subscriber.userId, subscriber.taskId);
            subscriber.close(ConnectionRegistry.REASON_ACCOUNT_DISABLED);
            return;
        }
        try {
            SseEmitter.SseEventBuilder builder = SseEmitter.event().name(event).data(data);
            if (eventId != null) builder.id(eventId.toString());
            subscriber.emitter.send(builder);
        } catch (IOException | IllegalStateException ignored) {
            subscriber.subscriberOpen.set(false);
            log.debug("blog-agent SSE event {} was dropped for closed task subscriber {}",
                    event, subscriber.taskId);
        }
    }

    private void followEvents(Long taskId, Long userId, long initialCursor,
                              SseSubscriber subscriber) {
        long cursor = initialCursor;
        try {
            while (subscriber.isOpen()) {
                List<BlogAgentEvent> events = eventService.listAfter(taskId, cursor);
                if (events.isEmpty()) {
                    BlogAgentTaskVO snapshot = service.get(taskId, userId);
                    if (isTerminal(snapshot)) {
                        if ("ready".equals(snapshot.getTask().getStatus())) {
                            send(subscriber, "complete", snapshot);
                        } else {
                            send(subscriber, "error",
                                    defaultErrorMessage(snapshot.getTask().getErrorMessage()));
                        }
                        return;
                    }
                    Thread.sleep(500);
                    continue;
                }
                for (BlogAgentEvent event : events) {
                    if (event.getId() != null) cursor = event.getId();
                    send(subscriber, event);
                    if ("complete".equals(event.getEventType())
                            || "error".equals(event.getEventType())) {
                        return;
                    }
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException exception) {
            send(subscriber, "error", safeMessage(exception));
        } finally {
            subscriber.heartbeatCancel();
            completeSubscriber(subscriber);
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

    private void sendHeartbeat(SseSubscriber subscriber) {
        if (!subscriber.isOpen()) return;
        // 心跳同样触发封禁校验：这样即使业务长时间无事件，也能在 SLO 内断开
        // （V9 §50「Heartbeat 也应触发检查」）。§28.4 提醒：心跳只是兜底，
        // 主要手段是 reconciler 的主动断开。
        if (securityStateCache.isDisabled(subscriber.userId)) {
            log.info("[Security] 账号已封禁，心跳终止 SSE account={} task={}",
                    subscriber.userId, subscriber.taskId);
            subscriber.close(ConnectionRegistry.REASON_ACCOUNT_DISABLED);
            return;
        }
        try {
            subscriber.emitter.send(SseEmitter.event().comment("heartbeat"));
        } catch (IOException | IllegalStateException ignored) {
            subscriber.subscriberOpen.set(false);
            log.debug("blog-agent SSE heartbeat was dropped for closed task subscriber {}",
                    subscriber.taskId);
        }
    }

    private void completeSubscriber(SseSubscriber subscriber) {
        connectionRegistry.unregister(subscriber);
        if (subscriber.subscriberOpen.getAndSet(false)) {
            try {
                subscriber.emitter.complete();
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
        return "博客生成助手任务失败，请稍后重试";
    }

    private String defaultErrorMessage(String message) {
        return message == null || message.isBlank() ? "博客生成助手任务失败" : message;
    }

    /**
     * 一条 blog-agent SSE 订阅的连接句柄（V9 §49 / §50）。
     *
     * <p>把「任务、账号、emitter、开闭标志、心跳任务」打成一个对象：既让发送出口
     * 一次拿到全部上下文，也让封禁时能从注册表反查到需要关闭的连接。
     * 做成静态嵌套类是为了让用例能直接构造它，而不必绕开控制器的依赖装配。</p>
     */
    static final class SseSubscriber implements ConnectionRegistry.ConnectionHandle {
        private final ConnectionRegistry connectionRegistry;
        private final Long taskId;
        private final Long userId;
        private final SseEmitter emitter;
        private final AtomicBoolean subscriberOpen = new AtomicBoolean(true);
        private volatile ScheduledFuture<?> heartbeat;

        SseSubscriber(ConnectionRegistry connectionRegistry, Long taskId, Long userId,
                      SseEmitter emitter) {
            this.connectionRegistry = connectionRegistry;
            this.taskId = taskId;
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

        /** 封禁路径入口（V9 §50 手段 A）：以 403 结束该条流，而不是静默丢弃事件。 */
        @Override
        public void close(String reason) {
            if (!subscriberOpen.getAndSet(false)) return;
            heartbeatCancel();
            connectionRegistry.unregister(this);
            try {
                emitter.completeWithError(new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        reason == null ? ConnectionRegistry.REASON_ACCOUNT_DISABLED : reason));
            } catch (IllegalStateException ignored) {
                try {
                    emitter.complete();
                } catch (IllegalStateException ignoredAgain) {
                    // The client or container already completed the response.
                }
            }
        }

        void heartbeatCancel() {
            ScheduledFuture<?> current = heartbeat;
            if (current != null) current.cancel(false);
        }

        boolean isOpen() {
            return subscriberOpen.get();
        }
    }
}
