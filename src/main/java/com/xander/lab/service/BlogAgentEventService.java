package com.xander.lab.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.entity.BlogAgentEvent;
import com.xander.lab.mapper.BlogAgentEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class BlogAgentEventService {
    private static final int DELTA_FLUSH_CHARS = 512;
    private static final long DELTA_FLUSH_NANOS = 250_000_000L;
    private static final int REPLAY_BATCH_SIZE = 200;

    private final BlogAgentEventMapper eventMapper;
    private final ObjectMapper objectMapper;

    public EventSession openSession(Long taskId, Consumer<BlogAgentEvent> onStoredEvent) {
        return new EventSession(taskId, onStoredEvent);
    }

    public List<BlogAgentEvent> listAfter(Long taskId, long afterEventId) {
        return eventMapper.selectList(new LambdaQueryWrapper<BlogAgentEvent>()
                .eq(BlogAgentEvent::getTaskId, taskId)
                .gt(BlogAgentEvent::getId, afterEventId)
                .orderByAsc(BlogAgentEvent::getId)
                .last("LIMIT " + REPLAY_BATCH_SIZE));
    }

    public void clearTaskEvents(Long taskId) {
        eventMapper.delete(new LambdaQueryWrapper<BlogAgentEvent>()
                .eq(BlogAgentEvent::getTaskId, taskId));
    }

    private void append(Long taskId, String eventType, Object data,
                        Consumer<BlogAgentEvent> onStoredEvent) {
        BlogAgentEvent event = new BlogAgentEvent();
        event.setTaskId(taskId);
        event.setEventType(eventType);
        event.setDataContent(serialize(data));
        event.setCreatedAt(LocalDateTime.now());
        try {
            eventMapper.insert(event);
        } catch (RuntimeException exception) {
            log.warn("blog-agent event persistence failed for task {} and event {}: {}",
                    taskId, eventType, exception.getMessage());
        }
        try {
            onStoredEvent.accept(event);
        } catch (RuntimeException exception) {
            log.debug("blog-agent live event delivery failed for task {} and event {}",
                    taskId, eventType);
        }
    }

    private String serialize(Object data) {
        if (data instanceof String text) return text;
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("博客智能体事件序列化失败", exception);
        }
    }

    public final class EventSession implements AutoCloseable {
        private final Long taskId;
        private final Consumer<BlogAgentEvent> onStoredEvent;
        private final StringBuilder deltaBuffer = new StringBuilder();
        private long lastDeltaFlush = System.nanoTime();
        private boolean closed;

        private EventSession(Long taskId, Consumer<BlogAgentEvent> onStoredEvent) {
            this.taskId = taskId;
            this.onStoredEvent = onStoredEvent;
        }

        public synchronized void accept(String eventType, Object data) {
            if (closed) return;
            if ("delta".equals(eventType)) {
                deltaBuffer.append(data == null ? "" : data);
                long now = System.nanoTime();
                if (deltaBuffer.length() >= DELTA_FLUSH_CHARS
                        || now - lastDeltaFlush >= DELTA_FLUSH_NANOS) {
                    flushDelta(now);
                }
                return;
            }
            flushDelta(System.nanoTime());
            append(taskId, eventType, data, onStoredEvent);
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            flushDelta(System.nanoTime());
            closed = true;
        }

        private void flushDelta(long now) {
            if (deltaBuffer.isEmpty()) return;
            String delta = deltaBuffer.toString();
            deltaBuffer.setLength(0);
            lastDeltaFlush = now;
            append(taskId, "delta", delta, onStoredEvent);
        }
    }
}
