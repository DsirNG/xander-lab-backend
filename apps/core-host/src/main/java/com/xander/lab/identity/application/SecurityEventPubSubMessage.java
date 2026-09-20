package com.xander.lab.identity.application;

import com.xander.lab.identity.domain.SecurityEventOutboxEntry;

/** Redis Pub/Sub 内部消息格式；不暴露发件箱的重试与数据库状态字段。 */
public record SecurityEventPubSubMessage(
        Long id,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payload) {

    public static SecurityEventPubSubMessage from(SecurityEventOutboxEntry entry) {
        return new SecurityEventPubSubMessage(
                entry.getId(),
                entry.getAggregateType(),
                entry.getAggregateId(),
                entry.getEventType(),
                entry.getPayload());
    }

    public SecurityEventOutboxEntry toOutboxEntry() {
        SecurityEventOutboxEntry entry = new SecurityEventOutboxEntry();
        entry.setId(id);
        entry.setAggregateType(aggregateType);
        entry.setAggregateId(aggregateId);
        entry.setEventType(eventType);
        entry.setPayload(payload);
        return entry;
    }
}
