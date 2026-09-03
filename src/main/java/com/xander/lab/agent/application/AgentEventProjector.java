package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentEvent;
import com.xander.lab.agent.domain.AgentMessage;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import com.xander.lab.agent.infrastructure.AgentMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Consumer;

/** Projects active-run messages and legacy replay events from execution facts. */
@Service
@RequiredArgsConstructor
public class AgentEventProjector {
    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final AgentEventService eventService;
    private final TransactionTemplate transactionTemplate;

    void saveActiveMessage(Long conversationId, Long userId, long runVersion,
                           String role, String kind, String toolName, String content) {
        AgentMessage message = new AgentMessage();
        message.setConversationId(conversationId);
        message.setRunVersion(runVersion);
        message.setRole(role);
        message.setKind(kind);
        message.setToolName(toolName);
        message.setContent(content);
        transactionTemplate.executeWithoutResult(status -> {
            int touched = renewActiveRun(conversationId, userId, runVersion);
            if (touched != 1 || messageMapper.insertForActiveRun(message, userId, runVersion) != 1) {
                throw new AgentRunSupersededException(conversationId, runVersion);
            }
        });
    }

    /** One user turn owns one plan card; later plan updates replace that card's contents. */
    void saveOrUpdateActivePlanMessage(Long conversationId, Long userId, long runVersion, String content) {
        transactionTemplate.executeWithoutResult(status -> {
            if (renewActiveRun(conversationId, userId, runVersion) != 1) {
                throw new AgentRunSupersededException(conversationId, runVersion);
            }
            int updated = messageMapper.update(null, new LambdaUpdateWrapper<AgentMessage>()
                    .eq(AgentMessage::getConversationId, conversationId)
                    .eq(AgentMessage::getRunVersion, runVersion)
                    .eq(AgentMessage::getKind, "plan")
                    .set(AgentMessage::getContent, content));
            if (updated > 0) return;

            AgentMessage message = new AgentMessage();
            message.setConversationId(conversationId);
            message.setRunVersion(runVersion);
            message.setRole("assistant");
            message.setKind("plan");
            message.setContent(content);
            if (messageMapper.insertForActiveRun(message, userId, runVersion) != 1) {
                throw new AgentRunSupersededException(conversationId, runVersion);
            }
        });
    }

    AgentEvent appendActive(Long conversationId, Long userId, long runVersion,
                            String eventType, Object data, Consumer<AgentEvent> listener) {
        AgentEvent event = eventService.storeActive(conversationId, userId, runVersion, eventType, data);
        eventService.deliver(event, listener);
        return event;
    }

    JsonNode progressPayload(String tool, String message) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("tool", tool);
        String raw = message == null ? "" : message;
        int separator = raw.indexOf('|');
        if (separator > 0) {
            payload.put("stage", raw.substring(0, separator));
            payload.put("message", raw.substring(separator + 1));
        } else {
            payload.put("message", raw);
        }
        return payload;
    }

    private int renewActiveRun(Long conversationId, Long userId, long runVersion) {
        // A message projection also proves that this worker still owns the durable lease.
        return conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                .eq(AgentConversation::getId, conversationId)
                .eq(AgentConversation::getUserId, userId)
                .eq(AgentConversation::getStatus, "running")
                .eq(AgentConversation::getRunVersion, runVersion)
                .setSql("updated_at = CURRENT_TIMESTAMP"));
    }
}
