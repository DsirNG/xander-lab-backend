package com.xander.lab.agent.api;

import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentEvent;
import com.xander.lab.agent.domain.AgentMessage;
import com.xander.lab.agent.application.AgentConversationService;
import com.xander.lab.agent.application.AgentAttachmentService;
import com.xander.lab.agent.application.AgentEventService;
import com.xander.lab.agent.application.AgentApprovalService;
import com.xander.lab.common.Result;
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

    @Autowired
    public AgentConversationController(AgentConversationService service,
                                       AgentAttachmentService attachmentService,
                                       AgentEventService eventService,
                                       AgentApprovalService approvalService,
                                       @Qualifier("agentTaskExecutor") TaskExecutor agentTaskExecutor,
                                       @Qualifier("agentSseExecutor") TaskExecutor agentSseExecutor,
                                       @Qualifier("agentHeartbeatScheduler") TaskScheduler agentHeartbeatScheduler) {
        this.service = service;
        this.attachmentService = attachmentService;
        this.eventService = eventService;
        this.approvalService = approvalService;
        this.agentTaskExecutor = agentTaskExecutor;
        this.agentSseExecutor = agentSseExecutor;
        this.agentHeartbeatScheduler = agentHeartbeatScheduler;
    }

    /** Compatibility constructor for focused controller tests that do not exercise approvals. */
    AgentConversationController(AgentConversationService service,
                                AgentAttachmentService attachmentService,
                                AgentEventService eventService,
                                TaskExecutor agentTaskExecutor,
                                TaskExecutor agentSseExecutor,
                                TaskScheduler agentHeartbeatScheduler) {
        this(service, attachmentService, eventService, null, agentTaskExecutor, agentSseExecutor,
                agentHeartbeatScheduler);
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

    /**
     * 用户主动"继续"一个可恢复的执行（{@code interrupted} / {@code budget_exhausted} /
     * {@code paused} / {@code awaiting_approval}）。
     *
     * <p>与 {@code /{id}/cancel} 互为镜像：那个让一轮停下来，这个让它接着走。走的是 reaper
     * 自动恢复用的同一条 {@code resume()}，所以"什么算同一件事"只有一套规则。</p>
     *
     * <p><b>为什么恢复之后必须在这里派活。</b>{@code service.resume()} 只把状态推回 {@code running}
     * 并清掉陈旧的停止请求，它不执行任何东西。只改状态的话，会话会停在 {@code running} 而无人执行，
     * 用户看到"正在生成"却永远等不到结果——所以提交失败时必须按 503 收口并把这一轮标记为失败，
     * 不能留下一个"看起来在跑、其实没人跑"的会话。</p>
     *
     * @return 恢复后的会话；状态不可恢复或记录缺失时 409，工作线程池拒绝时 503
     */
    @PostMapping("/{id}/resume")
    public Result<AgentConversation> resume(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        AgentConversation resumed = service.resume(id, userId);
        long runVersion = resumed.getRunVersion() == null ? 0L : resumed.getRunVersion();
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    // 恢复没有新的用户输入，所以 message 传 null：这一轮接着原来的意图走。
                    service.executeClaimedTurn(id, userId, runVersion, null, false, event -> {
                    });
                } catch (Exception exception) {
                    // executeClaimedTurn persists and delivers the durable error event.
                    log.debug("agent resumed turn ended exceptionally for conversation {}", id);
                }
            }, agentTaskExecutor);
        } catch (RuntimeException exception) {
            service.failClaimedTurn(id, userId, runVersion, "智能体执行队列繁忙，请稍后重试");
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "智能体执行队列繁忙，请稍后重试", exception);
        }
        return Result.success(resumed);
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
        ScheduledFuture<?> heartbeat;
        try {
            heartbeat = scheduleHeartbeat(id, emitter, subscriberOpen);
        } catch (RuntimeException exception) {
            failClaimedStream(id, userId, runVersion, emitter, subscriberOpen, null);
            return emitter;
        }
        registerSubscriberCallbacks(id, emitter, subscriberOpen, heartbeat);
        send(id, emitter, subscriberOpen, null, "started",
                Map.of("phase", "understanding", "runVersion", runVersion));
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    service.executeClaimedTurn(id, userId, runVersion, content,
                            request.isDeepThinking(),
                            event -> send(id, emitter, subscriberOpen, event));
                } catch (Exception exception) {
                    // executeClaimedTurn persists and delivers the durable error event.
                    log.debug("agent claimed turn ended exceptionally for conversation {}", id);
                } finally {
                    heartbeat.cancel(false);
                    completeSubscriber(emitter, subscriberOpen);
                }
            }, agentTaskExecutor);
        } catch (RuntimeException exception) {
            failClaimedStream(id, userId, runVersion, emitter, subscriberOpen, heartbeat);
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
        ScheduledFuture<?> heartbeat;
        try {
            heartbeat = scheduleHeartbeat(id, emitter, subscriberOpen);
        } catch (RuntimeException exception) {
            completeSubscriber(emitter, subscriberOpen);
            throw serviceUnavailable(exception);
        }
        registerSubscriberCallbacks(id, emitter, subscriberOpen, heartbeat);
        send(id, emitter, subscriberOpen, null, "started",
                Map.of("phase", "recovering", "runVersion", runVersion));
        try {
            CompletableFuture.runAsync(
                    () -> followEvents(id, userId, runVersion, cursor,
                            emitter, subscriberOpen, heartbeat),
                    agentSseExecutor);
        } catch (RuntimeException exception) {
            heartbeat.cancel(false);
            completeSubscriber(emitter, subscriberOpen);
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

    ScheduledFuture<?> scheduleHeartbeat(Long conversationId, SseEmitter emitter,
                                         AtomicBoolean subscriberOpen) {
        return agentHeartbeatScheduler.scheduleAtFixedRate(
                () -> sendHeartbeat(conversationId, emitter, subscriberOpen),
                Instant.now().plus(HEARTBEAT_INTERVAL),
                HEARTBEAT_INTERVAL);
    }

    private void registerSubscriberCallbacks(Long conversationId, SseEmitter emitter,
                                             AtomicBoolean subscriberOpen,
                                             ScheduledFuture<?> heartbeat) {
        Runnable close = () -> {
            subscriberOpen.set(false);
            heartbeat.cancel(false);
            log.debug("agent SSE subscriber closed for conversation {}", conversationId);
        };
        emitter.onCompletion(close);
        emitter.onTimeout(close);
        emitter.onError(ignored -> close.run());
    }

    private void send(Long conversationId, SseEmitter emitter, AtomicBoolean subscriberOpen,
                      AgentEvent event) {
        send(conversationId, emitter, subscriberOpen, event.getId(),
                event.getEventType(), event.getDataContent());
    }

    private void send(Long conversationId, SseEmitter emitter, AtomicBoolean subscriberOpen,
                      Long eventId, String event, Object data) {
        if (!subscriberOpen.get()) return;
        try {
            SseEmitter.SseEventBuilder builder = SseEmitter.event().name(event).data(data);
            if (eventId != null) builder.id(eventId.toString());
            emitter.send(builder);
        } catch (IOException | IllegalStateException ignored) {
            subscriberOpen.set(false);
            log.debug("agent SSE event {} was dropped for closed subscriber of conversation {}",
                    event, conversationId);
        }
    }

    private void followEvents(Long conversationId, Long userId, long runVersion, long initialCursor,
                              SseEmitter emitter, AtomicBoolean subscriberOpen,
                              ScheduledFuture<?> heartbeat) {
        try {
            eventService.follow(conversationId, userId, runVersion, initialCursor,
                    event -> send(conversationId, emitter, subscriberOpen, event));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException exception) {
            log.warn("agent event recovery failed for conversation {}: {}",
                    conversationId, exception.getMessage());
        } finally {
            heartbeat.cancel(false);
            completeSubscriber(emitter, subscriberOpen);
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

    private void sendHeartbeat(Long conversationId, SseEmitter emitter, AtomicBoolean subscriberOpen) {
        if (!subscriberOpen.get()) return;
        try {
            emitter.send(SseEmitter.event().comment("heartbeat"));
        } catch (IOException | IllegalStateException ignored) {
            subscriberOpen.set(false);
            log.debug("agent SSE heartbeat dropped for closed subscriber of conversation {}",
                    conversationId);
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

    private void failClaimedStream(Long conversationId, Long userId, long runVersion,
                                   SseEmitter emitter, AtomicBoolean subscriberOpen,
                                   ScheduledFuture<?> heartbeat) {
        if (heartbeat != null) heartbeat.cancel(false);
        service.failClaimedTurn(conversationId, userId, runVersion,
                "智能体执行队列繁忙，请稍后重试");
        AgentEvent terminal = eventService.latestTerminal(conversationId, runVersion);
        if (terminal != null) send(conversationId, emitter, subscriberOpen, terminal);
        completeSubscriber(emitter, subscriberOpen);
    }

    private org.springframework.web.server.ResponseStatusException serviceUnavailable(RuntimeException cause) {
        return new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "智能体事件订阅繁忙，请稍后重试", cause);
    }

}
