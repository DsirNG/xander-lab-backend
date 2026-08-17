package com.xander.lab.agent.api;

import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentEvent;
import com.xander.lab.agent.domain.AgentMessage;
import com.xander.lab.agent.application.AgentConversationService;
import com.xander.lab.agent.application.AgentEventService;
import com.xander.lab.common.Result;
import com.xander.lab.identity.security.UserContext;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
    private final AgentEventService eventService;
    private final TaskExecutor agentTaskExecutor;
    private final TaskExecutor agentSseExecutor;
    private final TaskScheduler agentHeartbeatScheduler;

    public AgentConversationController(AgentConversationService service,
                                       AgentEventService eventService,
                                       @Qualifier("agentTaskExecutor") TaskExecutor agentTaskExecutor,
                                       @Qualifier("agentSseExecutor") TaskExecutor agentSseExecutor,
                                       @Qualifier("agentHeartbeatScheduler") TaskScheduler agentHeartbeatScheduler) {
        this.service = service;
        this.eventService = eventService;
        this.agentTaskExecutor = agentTaskExecutor;
        this.agentSseExecutor = agentSseExecutor;
        this.agentHeartbeatScheduler = agentHeartbeatScheduler;
    }

    /** 根据首条消息确定标题并创建 ready 会话壳；消息执行仍由 /messages/stream 负责。 */
    @PostMapping
    public Result<AgentConversationVO> create(
            @Valid @RequestBody AgentConversationMessageRequest request) {
        Long userId = UserContext.getUserId();
        AgentConversation conversation = service.create(userId, request.getContent());
        return Result.success(buildVO(conversation.getId(), userId));
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

    @PostMapping(value = "/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(@PathVariable Long id,
                                  @Valid @RequestBody AgentConversationMessageRequest request,
                                  HttpServletResponse response) {
        Long userId = UserContext.getUserId();
        // Claim and persist the user message before committing an SSE 200 response.
        AgentConversation claimed = service.prepareTurn(id, userId, request.getContent());
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
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    service.executeClaimedTurn(id, userId, runVersion,
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
        AgentConversation claimed = service.prepareTurn(id, userId, request.getContent());
        long runVersion = claimed.getRunVersion();
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    service.executeClaimedTurn(id, userId, runVersion, event -> {
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
        long cursor = initialCursor;
        try {
            while (subscriberOpen.get()) {
                List<AgentEvent> events = eventService.listAfter(conversationId, runVersion, cursor);
                if (events.isEmpty()) {
                    var snapshot = service.get(conversationId, userId);
                    boolean sameRun = Long.valueOf(runVersion).equals(snapshot.getRunVersion());
                    if (!sameRun || !"running".equals(snapshot.getStatus())) {
                        AgentEvent terminal = eventService.latestTerminal(conversationId, runVersion);
                        if (terminal != null && terminal.getId() != null && terminal.getId() > cursor) {
                            send(conversationId, emitter, subscriberOpen, terminal);
                        }
                        return;
                    }
                    Thread.sleep(500);
                    continue;
                }
                for (AgentEvent event : events) {
                    if (event.getId() != null) cursor = event.getId();
                    send(conversationId, emitter, subscriberOpen, event);
                    if ("complete".equals(event.getEventType())
                            || "error".equals(event.getEventType())) {
                        return;
                    }
                }
            }
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
