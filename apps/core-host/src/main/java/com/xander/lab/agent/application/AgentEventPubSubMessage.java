package com.xander.lab.agent.application;

import com.xander.lab.agent.domain.AgentEvent;

/** Redis Pub/Sub 内部消息格式；只携带订阅端推送与去重所需字段。 */
public record AgentEventPubSubMessage(
        Long id,
        Long conversationId,
        Long runVersion,
        String eventType,
        String dataContent) {

    public static AgentEventPubSubMessage from(AgentEvent event) {
        return new AgentEventPubSubMessage(
                event.getId(),
                event.getConversationId(),
                event.getRunVersion(),
                event.getEventType(),
                event.getDataContent());
    }

    public AgentEvent toAgentEvent() {
        AgentEvent event = new AgentEvent();
        event.setId(id);
        event.setConversationId(conversationId);
        event.setRunVersion(runVersion);
        event.setEventType(eventType);
        event.setDataContent(dataContent);
        return event;
    }
}
