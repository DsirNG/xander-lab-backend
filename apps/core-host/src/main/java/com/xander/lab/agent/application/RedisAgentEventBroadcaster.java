package com.xander.lab.agent.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.domain.AgentEvent;
import com.xander.lab.agent.infrastructure.AgentEventBusProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis Pub/Sub Agent 事件广播器；数据库重放是跨实例丢消息时的恢复路径。 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "agent.event-bus", name = "pubsub-enabled", havingValue = "true")
public class RedisAgentEventBroadcaster implements AgentEventBroadcaster {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentEventBusProperties properties;

    @Override
    public void publish(AgentEvent event) {
        String message;
        try {
            message = objectMapper.writeValueAsString(AgentEventPubSubMessage.from(event));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent 事件无法编码为 Pub/Sub 消息 id=" + event.getId(), exception);
        }
        Long receiverCount = redisTemplate.convertAndSend(properties.getPubsubChannel(), message);
        if (receiverCount == null || receiverCount <= 0L) {
            log.warn("agent Pub/Sub 当前没有订阅者 conversation={} event={} channel={}",
                    event.getConversationId(), event.getId(), properties.getPubsubChannel());
        }
    }
}
