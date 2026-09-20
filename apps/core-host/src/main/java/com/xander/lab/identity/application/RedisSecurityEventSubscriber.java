package com.xander.lab.identity.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 本实例的安全事件 Redis Pub/Sub 订阅者。
 *
 * <p>收到消息后只调用 {@link SecurityEventOutboxPublisher#deliverLocally}，绝不重新广播，
 * 从而避免 Pub/Sub 回环。解析或应用失败只记录日志；数据库 reconciler 会按 SOT 兜底。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "auth.refresh", name = "security-pubsub-enabled", havingValue = "true")
public class RedisSecurityEventSubscriber implements MessageListener {

    private final ObjectMapper objectMapper;
    private final SecurityEventOutboxPublisher outboxPublisher;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String raw = new String(message.getBody(), StandardCharsets.UTF_8);
            SecurityEventPubSubMessage event = objectMapper.readValue(raw, SecurityEventPubSubMessage.class);
            outboxPublisher.deliverLocally(event.toOutboxEntry());
        } catch (Exception ex) {
            // Pub/Sub 没有 ack/replay；异常不能打断监听线程，reconciler 会兜底恢复状态。
            log.error("[Security] Pub/Sub 安全事件处理失败，将由 reconciler 兜底: {}", ex.getMessage(), ex);
        }
    }
}
