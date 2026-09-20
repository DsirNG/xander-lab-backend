package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** 本实例的 Agent 事件 Redis Pub/Sub 订阅者。 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "agent.event-bus", name = "pubsub-enabled", havingValue = "true")
public class RedisAgentEventSubscriber implements MessageListener {

    private final ObjectMapper objectMapper;
    private final AgentEventBus eventBus;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String raw = new String(message.getBody(), StandardCharsets.UTF_8);
            AgentEventPubSubMessage event = objectMapper.readValue(raw, AgentEventPubSubMessage.class);
            if (event.id() == null) {
                log.warn("agent Pub/Sub 忽略无持久化 id 的事件 conversation={}", event.conversationId());
                return;
            }
            eventBus.publishLocally(event.conversationId(), event.toAgentEvent());
        } catch (Exception exception) {
            // Pub/Sub 无 ack/replay；监听线程不能被坏消息打断，订阅端可重新连接重放数据库。
            log.error("agent Pub/Sub 事件处理失败，将由数据库重放兜底: {}", exception.getMessage(), exception);
        }
    }
}
