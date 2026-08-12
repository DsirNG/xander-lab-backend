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
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
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

    @PostMapping
    public Result<AgentConversationVO> create(@Valid @RequestBody AgentConversationMessageRequest request) {
        Long userId = UserContext.getUserId();
        var conversation = service.create(userId, request.getContent());
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

    @PostMapping("/{id}/cancel")
    public Result<AgentConversation> cancel(@PathVariable Long id) {
        return Result.success(service.cancel(id, UserContext.getUserId()));
    }

    @PostMapping(value = "/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(@PathVariable Long id,
                                  @Valid @RequestBody AgentConversationMessageRequest request,
                                  HttpServletResponse response) {
        Long userId = UserContext.getUserId();
        SseEmitter emitter = createEmitter(id, response);
        AtomicBoolean subscriberOpen = new AtomicBoolean(true);
        ScheduledFuture<?> heartbeat = scheduleHeartbeat(id, emitter, subscriberOpen);
        registerSubscriberCallbacks(id, emitter, subscriberOpen, heartbeat);
        CompletableFuture.runAsync(() -> {
            try {
                service.executeTurn(id, userId, request.getContent(),
                        (event, data) -> send(id, emitter, subscriberOpen, event, data));
            } catch (Exception exception) {
                send(id, emitter, subscriberOpen, "error", safeMessage(exception));
            } finally {
                heartbeat.cancel(false);
                completeSubscriber(emitter, subscriberOpen);
            }
        }, agentTaskExecutor);
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
                agentSseExecutor);
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
                      String event, Object data) {
        send(conversationId, emitter, subscriberOpen, null, event, data);
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

    private void followEvents(Long conversationId, Long userId, long initialCursor,
                              SseEmitter emitter, AtomicBoolean subscriberOpen,
                              ScheduledFuture<?> heartbeat) {
        long cursor = initialCursor;
        try {
            while (subscriberOpen.get()) {
                List<AgentEvent> events = eventService.listAfter(conversationId, cursor);
                if (events.isEmpty()) {
                    var snapshot = service.get(conversationId, userId);
                    if (!"running".equals(snapshot.getStatus())) {
                        send(conversationId, emitter, subscriberOpen, "complete", snapshot);
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
            send(conversationId, emitter, subscriberOpen, "error", safeMessage(exception));
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

    private String safeMessage(Exception exception) {
        if (exception instanceof org.springframework.web.server.ResponseStatusException response
                && response.getReason() != null) {
            return response.getReason();
        }
        if (exception instanceof IllegalArgumentException) {
            return exception.getMessage();
        }
        log.error("agent conversation turn failed", exception);
        return "智能体任务失败，请稍后重试";
    }
}