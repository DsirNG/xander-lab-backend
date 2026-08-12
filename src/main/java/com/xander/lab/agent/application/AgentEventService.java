package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.domain.AgentEvent;
import com.xander.lab.agent.infrastructure.AgentEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

/** Durable, replayable event stream of an agent conversation. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentEventService {
    private static final int REPLAY_BATCH_SIZE = 200;

    private final AgentEventMapper eventMapper;
    private final ObjectMapper objectMapper;

    public void append(Long conversationId, String eventType, Object data) {
        append(conversationId, eventType, data, ignored -> { });
    }

    public void append(Long conversationId, String eventType, Object data,
                       Consumer<AgentEvent> onStoredEvent) {
        AgentEvent event = new AgentEvent();
        event.setConversationId(conversationId);
        event.setEventType(eventType);
        event.setDataContent(serialize(data));
        event.setCreatedAt(LocalDateTime.now());
        try {
            eventMapper.insert(event);
        } catch (RuntimeException exception) {
            log.warn("agent event persistence failed for conversation {} and event {}: {}",
                    conversationId, eventType, exception.getMessage());
        }
        try {
            onStoredEvent.accept(event);
        } catch (RuntimeException exception) {
            log.debug("agent live event delivery failed for conversation {} and event {}",
                    conversationId, eventType);
        }
    }

    public List<AgentEvent> listAfter(Long conversationId, long afterEventId) {
        return eventMapper.selectList(new LambdaQueryWrapper<AgentEvent>()
                .eq(AgentEvent::getConversationId, conversationId)
                .gt(AgentEvent::getId, afterEventId)
                .orderByAsc(AgentEvent::getId)
                .last("LIMIT " + REPLAY_BATCH_SIZE));
    }

    public void clearConversationEvents(Long conversationId) {
        eventMapper.delete(new LambdaQueryWrapper<AgentEvent>()
                .eq(AgentEvent::getConversationId, conversationId));
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