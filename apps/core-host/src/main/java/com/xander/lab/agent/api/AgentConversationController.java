package com.xander.lab.agent.api;

import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentEvent;
import com.xander.lab.agent.domain.AgentMessage;
import com.xander.lab.agent.application.AgentConversationService;
import com.xander.lab.agent.application.AgentAttachmentService;
import com.xander.lab.agent.application.AgentEventService;
import com.xander.lab.agent.application.AgentApprovalService;
import com.xander.lab.common.Result;
import com.xander.lab.common.security.ConnectionRegistry;
import com.xander.lab.common.security.SecurityStateCache;
import com.xander.lab.identity.security.UserContext;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** REST + SSE facade for the orchestration agent conversations. */
@Slf4j
@RestController
@RequestMapping("/api/agent/conversations")
public class AgentConversationController {
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final AgentConversationService service;
    private final AgentAttachmentService attachmentService;
    private final AgentEventService eventService;
    private final AgentApprovalService approvalService;
    private final TaskExecutor agentTaskExecutor;
    private final TaskExecutor agentSseExecutor;
    private final TaskScheduler agentHeartbeatScheduler;
    /** 本实例的已封禁账号集合：SSE 发送前 O(1) 校验（V9 §50 手段 B）。 */
    private final SecurityStateCache securityStateCache;
    /** 本实例的活跃连接注册表：封禁时主动断开已建立的流（V9 §49 / §28.2 手段 A）。 */
    private final ConnectionRegistry connectionRegistry;

    @Autowired
    public AgentConversationController(AgentConversationService service,
                                       AgentAttachmentService attachmentService,
                                       AgentEventService eventService,
                                       AgentApprovalService approvalService,
                                       @Qualifier("agentTaskExecutor") TaskExecutor agentTaskExecutor,
                                       @Qualifier("agentSseExecutor") TaskExecutor agentSseExecutor,
                                       @Qualifier("agentHeartbeatScheduler") TaskScheduler agentHeartbeatScheduler,
                                       SecurityStateCache securityStateCache,
                                       ConnectionRegistry connectionRegistry) {
        this.service = service;
        this.attachmentService = attachmentService;
        this.eventService = eventService;
        this.approvalService = approvalService;
        this.agentTaskExecutor = agentTaskExecutor;
        this.agentSseExecutor = agentSseExecutor;
        this.agentHeartbeatScheduler = agentHeartbeatScheduler;
        this.securityStateCache = securityStateCache;
        this.connectionRegistry = connectionRegistry;
    }

    /** Compatibility constructor for focused controller tests that do not exercise approvals. */
    AgentConversationController(AgentConversationService service,
                                AgentAttachmentService attachmentService,
                                AgentEventService eventService,
                                TaskExecutor agentTaskExecutor,
                                TaskExecutor agentSseExecutor,
                                TaskScheduler agentHeartbeatScheduler) {
        this(service, attachmentService, eventService, null, agentTaskExecutor, agentSseExecutor,
                agentHeartbeatScheduler, new SecurityStateCache(), new ConnectionRegistry());
    }

    /**
     * Compatibility constructor for focused controller tests that need approvals
     * but not the security plane. Uses isolated (empty) cache/registry instances so the
     * ban path is inert; tests that exercise it should build the real beans explicitly.
     */
    AgentConversationController(AgentConversationService service,
                                AgentAttachmentService attachmentService,
                                AgentEventService eventService,
                                AgentApprovalService approvalService,
                                TaskExecutor agentTaskExecutor,
                                TaskExecutor agentSseExecutor,
                                TaskScheduler agentHeartbeatScheduler) {
        this(service, attachmentService, eventService, approvalService, agentTaskExecutor,
                agentSseExecutor, agentHeartbeatScheduler, new SecurityStateCache(),
                new ConnectionRegistry());
    }

    /** 根据首条消息确定标题并创建 ready 会话壳；消息执行仍由 /messages/stream 负责。 */
    @PostMapping
    public Result<AgentConversationVO> create(
            @Valid @RequestBody AgentConversationMessageRequest request) {
        Long userId = UserContext.getUserId();
        AgentConversation conversation = service.create(userId, request.effectiveContent());
        return Result.success(buildVO(conversation.getId(), userId));
    }

    /** Uploads an image or document for a later conversation turn. */
    @PostMapping("/attachments")
    public Result<com.xander.lab.agent.domain.AgentAttachment> uploadAttachment(
            @RequestParam("file") MultipartFile file) {
        return Result.success(attachmentService.upload(UserContext.getUserId(), file));
    }

    @GetMapping
    public Result<List<AgentConversation>> list() {
        return Result.success(service.listSessions(UserContext.getUserId()));
    }

    @GetMapping("/{id}")
    public Result<AgentConversationVO> get(@PathVariable Long id) {
        return Result.success(buildVO(id, UserContext.getUserId()));
    }

    @GetMapping("/{id}/messages")
    public Result<List<AgentMessage>> listMessages(@PathVariable Long id) {
        return Result.success(service.listMessages(id, UserContext.getUserId()));
    }

    @GetMapping("/shared/{shareToken}")
    public Result<AgentConversationVO> getShared(@PathVariable String shareToken) {
        AgentConversation conversation = service.getByShareToken(shareToken);
        List<AgentMessage> messages = service.listMessagesByShareToken(shareToken);
        return Result.success(AgentConversationVO.builder()
                .conversation(conversation)
                .messages(messages)
                .build());
    }

    @PostMapping("/{id}/share")
    public Result<String> enableShare(@PathVariable Long id) {
        String token = service.enableShare(id, UserContext.getUserId());
        return Result.success(token);
    }

    @DeleteMapping("/{id}/share")
    public Result<Void> disableShare(@PathVariable Long id) {
        service.disableShare(id, UserContext.getUserId());
        return Result.success(null);
    }

    @PostMapping("/{id}/cancel")
    public Result<AgentConversation> cancel(@PathVariable Long id) {
        return Result.success(service.cancel(id, UserContext.getUserId()));
    }

    /** Lists durable approval requests so reloads never lose a pending high-impact action. */
    @GetMapping("/{id}/approvals")
    public Result<List<com.xander.lab.agent.domain.AgentApproval>> approvals(@PathVariable Long id) {
        return Result.success(approvalService.list(id, UserContext.getUserId()));
    }

    /** Approves or rejects the exact persisted tool fingerprint without creating a new invocation. */
    @PostMapping("/{id}/approvals/{approvalId}/decision")
    public Result<com.xander.lab.agent.domain.AgentApproval> decideApproval(
            @PathVariable Long id, @PathVariable Long approvalId,
            @Valid @RequestBody AgentApprovalDecisionRequest request) {
        return Result.success(approvalService.decide(id, approvalId, UserContext.getUserId(),
                request.getApproved(), request.getReason()));
    }

    /** 上报已读：清掉会话列表上的"生成完成"提醒，不改变会话的排序时间。 */
    @PostMapping("/{id}/read")
    public Result<AgentConversation> markRead(@PathVariable Long id) {
        return Result.success(service.markRead(id, UserContext.getUserId()));
    }

    @PostMapping("/{id}/pin")
    public Result<AgentConversation> pin(@PathVariable Long id) {
        return Result.success(service.setPinned(id, UserContext.getUserId(), true));
    }

    @DeleteMapping("/{id}/pin")
    public Result<AgentConversation> unpin(@PathVariable Long id) {
        return Result.success(service.setPinned(id, UserContext.getUserId(), false));
    }

    @PostMapping(value = "/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(@PathVariable Long id,
                                  @Valid @RequestBody AgentConversationMessageRequest request,
                                  HttpServletResponse response) {
        Long userId = UserContext.getUserId();
        // Claim and persist the user message before committing an SSE 200 response.
        String content = request.effectiveContent();
        AgentConversation claimed = service.prepareTurn(id, userId, content,
                request.domainAttachments());
        long runVersion = claimed.getRunVersion();
        SseEmitter emitter = createEmitter(id, response);
        AtomicBoolean subscriberOpen = new AtomicBoolean(true);
        SseConnectionHandle handle = new SseConnectionHandle(id, userId, emitter, subscriberOpen);
        ScheduledFuture<?> heartbeat;
        try {
            heartbeat = scheduleHeartbeat(handle);
        } catch (RuntimeException exception) {
            failClaimedStream(id, userId, runVersion, handle, null);
            return emitter;
        }
        registerSubscriberCallbacks(handle, heartbeat);
        connectionRegistry.register(handle);
        send(handle, null, "started",
                Map.of("phase", "understanding", "runVersion", runVersion));
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    service.executeClaimedTurn(id, userId, runVersion, content,
                            request.isDeepThinking(),
                            event -> send(handle, event));
                } catch (Exception exception) {
                    // executeClaimedTurn persists and delivers the durable error event.
                    log.debug("agent claimed turn ended exceptionally for conversation {}", id);
                } finally {
                    heartbeat.cancel(false);
                    completeSubscriber(handle);
                }
            }, agentTaskExecutor);
        } catch (RuntimeException exception) {
            failClaimedStream(id, userId, runVersion, handle, heartbeat);
        }
        return emitter;
    }

    /**
     * 非流式触发端点：微信小程序无法读取 SSE 流，仅原子 claim 并在后台执行，
     * 事件全部落库、不向任何订阅者投递，前端以快照轮询作为状态事实来源。
     * 返回 runVersion 作为本轮执行标识；调度失败时标记 claim 失败并返回 503。
     */
    @PostMapping("/{id}/messages")
    public Result<Long> triggerMessage(@PathVariable Long id,
                                       @Valid @RequestBody AgentConversationMessageRequest request) {
        Long userId = UserContext.getUserId();
        String content = request.effectiveContent();
        AgentConversation claimed = service.prepareTurn(id, userId, content,
                request.domainAttachments());
        long runVersion = claimed.getRunVersion();
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    service.executeClaimedTurn(id, userId, runVersion, content,
                            request.isDeepThinking(), event -> {
                    });
                } catch (Exception exception) {
                    // executeClaimedTurn persists and delivers the durable error event.
                    log.debug("agent claimed turn ended exceptionally for conversation {}", id);
                }
            }, agentTaskExecutor);
        } catch (RuntimeException exception) {
            service.failClaimedTurn(id, userId, runVersion, "智能体执行队列繁忙，请稍后重试");
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "智能体执行队列繁忙，请稍后重试", exception);
        }
        return Result.success(runVersion);
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@PathVariable Long id,
                                @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                                @RequestParam(value = "afterEventId", required = false) Long afterEventId,
                                HttpServletResponse response) {
        Long userId = UserContext.getUserId();
        AgentConversation snapshot = service.get(id, userId);
        long runVersion = snapshot.getRunVersion() == null ? 0L : snapshot.getRunVersion();
        long cursor = resolveCursor(lastEventId, afterEventId);
        SseEmitter emitter = createEmitter(id, response);
        AtomicBoolean subscriberOpen = new AtomicBoolean(true);
        SseConnectionHandle handle = new SseConnectionHandle(id, userId, emitter, subscriberOpen);
        ScheduledFuture<?> heartbeat;
        try {
            heartbeat = scheduleHeartbeat(handle);
        } catch (RuntimeException exception) {
            completeSubscriber(handle);
            throw serviceUnavailable(exception);
        }
        registerSubscriberCallbacks(handle, heartbeat);
        connectionRegistry.register(handle);
        send(handle, null, "started",
                Map.of("phase", "recovering", "runVersion", runVersion));
        try {
            CompletableFuture.runAsync(
                    () -> followEvents(id, userId, runVersion, cursor, handle),
                    agentSseExecutor);
        } catch (RuntimeException exception) {
            heartbeat.cancel(false);
            completeSubscriber(handle);
            throw serviceUnavailable(exception);
        }
        return emitter;
    }

    private AgentConversationVO buildVO(Long conversationId, Long userId) {
        return AgentConversationVO.builder()
                .conversation(service.get(conversationId, userId))
                .messages(service.listMessages(conversationId, userId))
                .build();
    }

    private SseEmitter createEmitter(Long conversationId, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        log.debug("agent SSE subscriber opened for conversation {}", conversationId);
        return new SseEmitter(0L);
    }

    ScheduledFuture<?> scheduleHeartbeat(SseConnectionHandle handle) {
        ScheduledFuture<?> future = agentHeartbeatScheduler.scheduleAtFixedRate(
                () -> sendHeartbeat(handle),
                Instant.now().plus(HEARTBEAT_INTERVAL),
                HEARTBEAT_INTERVAL);
        handle.heartbeat = future;
        return future;
    }

    private void registerSubscriberCallbacks(SseConnectionHandle handle,
                                             ScheduledFuture<?> heartbeat) {
        Runnable close = () -> {
            handle.subscriberOpen.set(false);
            heartbeat.cancel(false);
            connectionRegistry.unregister(handle);
            log.debug("agent SSE subscriber closed for conversation {}", handle.conversationId);
        };
        handle.emitter.onCompletion(close);
        handle.emitter.onTimeout(close);
        handle.emitter.onError(ignored -> close.run());
    }

    private void send(SseConnectionHandle handle, AgentEvent event) {
        send(handle, event.getId(), event.getEventType(), event.getDataContent());
    }

    private void send(SseConnectionHandle handle, Long eventId, String event, Object data) {
        if (!handle.subscriberOpen.get()) return;
        // V9 §50 手段 B（兜底）：SseEmitter(0L) 永不超时，所以每次发送前都要校验封禁状态。
        // 手段 A（reconciler 主动断开）是主要手段；这里保证即使事件丢失也不会继续推送。
        if (securityStateCache.isDisabled(handle.accountId)) {
            log.info("[Security] 账号已封禁，终止已建立的 SSE 推送 account={}", handle.accountId);
            completeSubscriber(handle);
            return;
        }
        try {
            SseEmitter.SseEventBuilder builder = SseEmitter.event().name(event).data(data);
            if (eventId != null) builder.id(eventId.toString());
            handle.emitter.send(builder);
        } catch (IOException | IllegalStateException ignored) {
            handle.subscriberOpen.set(false);
            log.debug("agent SSE event {} was dropped for closed subscriber of conversation {}",
                    event, handle.conversationId);
        }
    }

    private void followEvents(Long conversationId, Long userId, long runVersion, long initialCursor,
                              SseConnectionHandle handle) {
        try {
            eventService.follow(conversationId, userId, runVersion, initialCursor,
                    event -> send(handle, event));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException exception) {
            log.warn("agent event recovery failed for conversation {}: {}",
                    conversationId, exception.getMessage());
        } finally {
            handle.heartbeatCancel();
            completeSubscriber(handle);
        }
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

    private void sendHeartbeat(SseConnectionHandle handle) {
        if (!handle.subscriberOpen.get()) return;
        // 心跳同样触发封禁校验：这样即使业务长时间无事件，也能在 SLO 内断开
        // （V9 §50「Heartbeat 也应触发检查」）。
        // 注意 §28.4：心跳只是兜底，主要手段是 reconciler 的主动断开；
        // 若要把心跳当主要手段，interval 必须 <= 2 秒并写进 contract-changes.yaml。
        if (securityStateCache.isDisabled(handle.accountId)) {
            log.info("[Security] 账号已封禁，心跳终止 SSE account={}", handle.accountId);
            completeSubscriber(handle);
            return;
        }
        try {
            handle.emitter.send(SseEmitter.event().comment("heartbeat"));
        } catch (IOException | IllegalStateException ignored) {
            handle.subscriberOpen.set(false);
            log.debug("agent SSE heartbeat dropped for closed subscriber of conversation {}",
                    handle.conversationId);
        }
    }

    private void completeSubscriber(SseConnectionHandle handle) {
        connectionRegistry.unregister(handle);
        if (handle.subscriberOpen.getAndSet(false)) {
            try {
                handle.emitter.complete();
            } catch (IllegalStateException ignored) {
                // The client or container already completed the response.
            }
        }
    }

    private void failClaimedStream(Long conversationId, Long userId, long runVersion,
                                   SseConnectionHandle handle,
                                   ScheduledFuture<?> heartbeat) {
        if (heartbeat != null) heartbeat.cancel(false);
        service.failClaimedTurn(conversationId, userId, runVersion,
                "智能体执行队列繁忙，请稍后重试");
        AgentEvent terminal = eventService.latestTerminal(conversationId, runVersion);
        if (terminal != null) send(handle, terminal);
        completeSubscriber(handle);
    }

    private org.springframework.web.server.ResponseStatusException serviceUnavailable(RuntimeException cause) {
        return new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "智能体事件订阅繁忙，请稍后重试", cause);
    }

    /**
     * 一条 SSE 订阅的连接句柄（V9 §49）。
     *
     * <p>把「会话、账号、emitter、开闭标志、心跳任务」打成一个对象，
     * 既让发送边界能一次拿到全部上下文，也让封禁时可以从注册表里
     * 反查到需要关闭的连接。</p>
     *
     * <p>{@link #close(String)} 是封禁路径的入口：它把流以 403 结束，
     * 触发 {@code onError} 回调，从而取消心跳并把自己从注册表摘掉。</p>
     */
    final class SseConnectionHandle implements ConnectionRegistry.ConnectionHandle {
        private final Long conversationId;
        private final Long accountId;
        private final SseEmitter emitter;
        private final AtomicBoolean subscriberOpen;
        private volatile ScheduledFuture<?> heartbeat;

        SseConnectionHandle(Long conversationId, Long accountId, SseEmitter emitter,
                            AtomicBoolean subscriberOpen) {
            this.conversationId = conversationId;
            this.accountId = accountId;
            this.emitter = emitter;
            this.subscriberOpen = subscriberOpen;
        }

        @Override
        public Long accountId() {
            return accountId;
        }

        @Override
        public String kind() {
            return ConnectionRegistry.KIND_SSE;
        }

        @Override
        public void close(String reason) {
            if (!subscriberOpen.getAndSet(false)) {
                return;
            }
            heartbeatCancel();
            connectionRegistry.unregister(this);
            try {
                emitter.completeWithError(new ResponseStatusException(HttpStatus.FORBIDDEN, reason));
            } catch (IllegalStateException ignored) {
                try {
                    emitter.complete();
                } catch (IllegalStateException ignoredAgain) {
                    // 客户端或容器已经结束了响应
                }
            }
        }

        void heartbeatCancel() {
            ScheduledFuture<?> current = heartbeat;
            if (current != null) {
                current.cancel(false);
            }
        }
    }

}
