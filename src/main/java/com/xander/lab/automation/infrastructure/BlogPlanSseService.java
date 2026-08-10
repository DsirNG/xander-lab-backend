package com.xander.lab.automation.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** User-level notification stream. Each logged-in tab subscribes once to
 * {@code GET /api/notifications/events}; background plan runs push through the
 * in-memory registry below. The blog_notification table remains the durable
 * fallback for offline users, so this stream is loss-tolerant by design. */
@Slf4j
@Component
public class BlogPlanSseService {

    private final ObjectMapper objectMapper;
    private final Map<Long, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "blog-plan-sse-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    public BlogPlanSseService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.heartbeat.scheduleAtFixedRate(this::beatAll, 25, 25, TimeUnit.SECONDS);
    }

    /** Registers one connection and primes callbacks so per-user lists never
     * retain completed emitters. */
    public SseEmitter subscribe(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("SSE 连接需要已登录用户");
        }
        SseEmitter emitter = new SseEmitter(0L);
        List<SseEmitter> userEmitters =
                subscribers.computeIfAbsent(userId, key -> new CopyOnWriteArrayList<>());
        userEmitters.add(emitter);
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(error -> remove(userId, emitter));
        try {
            // Flush headers immediately and give clients an explicit ready signal.
            emitter.send(SseEmitter.event().name("connected").data("{\"status\":\"connected\"}"));
            log.info("[SSE] 已连接 userId={}, activeConnections={}", userId, userEmitters.size());
        } catch (IOException | IllegalStateException exception) {
            log.warn("[SSE] 建立连接失败 userId={}: {}", userId, exception.getMessage());
            remove(userId, emitter);
        }
        return emitter;
    }

    /** Pushes a live event to every connected tab of the user. Never throws. */
    public void push(Long userId, String type, String title, String message,
                     Long planId, Long runId) {
        if (userId == null) return;
        List<SseEmitter> userEmitters = subscribers.get(userId);
        if (userEmitters == null || userEmitters.isEmpty()) return;
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
        for (SseEmitter emitter : userEmitters) {
            try {
                emitter.send(SseEmitter.event().name("notification").data(payload));
            } catch (IOException | IllegalStateException dropped) {
                remove(userId, emitter);
            }
        }
    }

    /** Broadcasts a live-only diagnostic message and returns its recipient count. */
    public int broadcast(String type, String title, String message, Long planId, Long runId) {
        int recipients = 0;
        for (Long userId : subscribers.keySet()) {
            List<SseEmitter> userEmitters = subscribers.get(userId);
            if (userEmitters != null) recipients += userEmitters.size();
            push(userId, type, title, message, planId, runId);
        }
        log.info("[SSE] 广播测试已发送 activeConnections={}", recipients);
        return recipients;
    }

    private void beatAll() {
        for (Map.Entry<Long, List<SseEmitter>> entry : subscribers.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                } catch (IOException | IllegalStateException dropped) {
                    remove(entry.getKey(), emitter);
                }
            }
        }
    }

    private void remove(Long userId, SseEmitter emitter) {
        List<SseEmitter> userEmitters = subscribers.get(userId);
        if (userEmitters != null) {
            userEmitters.remove(emitter);
            if (userEmitters.isEmpty()) {
                subscribers.remove(userId, userEmitters);
            }
        }
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // Connection already closed.
        }
        log.info("[SSE] 已断开 userId={}", userId);
    }
}
