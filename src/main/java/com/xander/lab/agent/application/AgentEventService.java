package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.domain.AgentEvent;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.infrastructure.AgentEventMapper;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Consumer;

/** Durable, replayable event stream of an agent conversation. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentEventService {
    private static final int REPLAY_BATCH_SIZE = 200;

    private final AgentEventMapper eventMapper;
    private final AgentConversationMapper conversationMapper;
    private final ObjectMapper objectMapper;

    /** Persists an event for the active generation and renews its lease. */
    @Transactional
    public AgentEvent storeActive(Long conversationId, Long userId, long runVersion,
                                  String eventType, Object data) {
        int touched = conversationMapper.update(null, new LambdaUpdateWrapper<com.xander.lab.agent.domain.AgentConversation>()
                .eq(com.xander.lab.agent.domain.AgentConversation::getId, conversationId)
                .eq(com.xander.lab.agent.domain.AgentConversation::getUserId, userId)
                .eq(com.xander.lab.agent.domain.AgentConversation::getStatus, "running")
                .eq(com.xander.lab.agent.domain.AgentConversation::getRunVersion, runVersion)
                .setSql("updated_at = CURRENT_TIMESTAMP"));
        if (touched != 1) throw new AgentRunSupersededException(conversationId, runVersion);
        return storeForRun(conversationId, userId, runVersion, eventType, data, true);
    }

    /** Stores an event for a generation; terminal callers may already have moved it out of running. */
    public AgentEvent storeForRun(Long conversationId, Long userId, long runVersion,
                                  String eventType, Object data, boolean requireRunning) {
        AgentEvent event = new AgentEvent();
        event.setConversationId(conversationId);
        event.setRunVersion(runVersion);
        event.setEventType(eventType);
        event.setDataContent(serialize(data));
        int inserted = eventMapper.insertForRun(event, userId, requireRunning);
        if (inserted != 1 || event.getId() == null) {
            if (inserted == 0) throw new AgentRunSupersededException(conversationId, runVersion);
            throw new IllegalStateException("智能体事件持久化后未返回事件游标");
        }
        return event;
    }

    /** Delivers an already-stored event; subscriber disconnects never affect durable execution. */
    public void deliver(AgentEvent event, Consumer<AgentEvent> onStoredEvent) {
        try {
            onStoredEvent.accept(event);
        } catch (RuntimeException exception) {
            log.debug("agent live event delivery failed for conversation {} and event {}",
                    event.getConversationId(), event.getEventType());
        }
    }

    public List<AgentEvent> listAfter(Long conversationId, long runVersion, long afterEventId) {
        return eventMapper.selectList(new LambdaQueryWrapper<AgentEvent>()
                .eq(AgentEvent::getConversationId, conversationId)
                .eq(AgentEvent::getRunVersion, runVersion)
                .gt(AgentEvent::getId, afterEventId)
                .orderByAsc(AgentEvent::getId)
                .last("LIMIT " + REPLAY_BATCH_SIZE));
    }

    /** Returns the latest persisted terminal event of the current retained turn, if one exists. */
    public AgentEvent latestTerminal(Long conversationId, long runVersion) {
        List<AgentEvent> events = eventMapper.selectList(new LambdaQueryWrapper<AgentEvent>()
                .eq(AgentEvent::getConversationId, conversationId)
                .eq(AgentEvent::getRunVersion, runVersion)
                .in(AgentEvent::getEventType, List.of("complete", "error"))
                .orderByDesc(AgentEvent::getId)
                .last("LIMIT 1"));
        return events.isEmpty() ? null : events.get(0);
    }

    /**
     * 从游标开始跟随一轮执行：重放已持久化事件，无新事件时轮询等待，
     * 直到该轮出现终态（complete/error）或会话被新一轮次取代为止。
     * SSE 与 WebSocket 订阅共用此逻辑；订阅端断开只影响本次跟随，不打断持久化执行。
     */
    public void follow(Long conversationId, Long userId, long runVersion, long initialCursor,
                       Consumer<AgentEvent> sink) throws InterruptedException {
        long cursor = initialCursor;
        while (true) {
            List<AgentEvent> events = listAfter(conversationId, runVersion, cursor);
            if (events.isEmpty()) {
                AgentConversation snapshot = conversationMapper.selectById(conversationId);
                boolean sameRun = snapshot != null
                        && Long.valueOf(runVersion).equals(snapshot.getRunVersion())
                        && userId != null && userId.equals(snapshot.getUserId());
                if (!sameRun || !"running".equals(snapshot.getStatus())) {
                    AgentEvent terminal = latestTerminal(conversationId, runVersion);
                    if (terminal != null && terminal.getId() != null && terminal.getId() > cursor) {
                        sink.accept(terminal);
                    }
                    return;
                }
                Thread.sleep(500);
                continue;
            }
            for (AgentEvent event : events) {
                if (event.getId() != null) cursor = event.getId();
                sink.accept(event);
                if ("complete".equals(event.getEventType())
                        || "error".equals(event.getEventType())) {
                    return;
                }
            }
        }
    }

    private String serialize(Object data) {
        if (data instanceof String text) return text;
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("智能体事件序列化失败", exception);
        }
    }
}
