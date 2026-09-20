package com.xander.lab.identity.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.identity.domain.SecurityEventOutboxEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis Pub/Sub 安全事件广播器。
 *
 * <p>Redis Pub/Sub 是至少一次、非持久化的快速通道；发送失败交给发件箱退避，
 * 订阅者漏收则由 {@link SecurityStateReconciler} 按数据库事实兜底收敛。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "auth.refresh", name = "security-pubsub-enabled", havingValue = "true")
public class RedisSecurityEventBroadcaster implements SecurityEventBroadcaster {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RefreshTokenProperties properties;

    @Override
    public void publish(SecurityEventOutboxEntry entry) {
        String message;
        try {
            message = objectMapper.writeValueAsString(SecurityEventPubSubMessage.from(entry));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("安全事件无法编码为 Pub/Sub 消息 id=" + entry.getId(), ex);
        }

        Long receiverCount = redisTemplate.convertAndSend(properties.getSecurityPubsubChannel(), message);
        if (receiverCount == null || receiverCount <= 0L) {
            throw new IllegalStateException("安全事件 Pub/Sub 没有订阅者 channel="
                    + properties.getSecurityPubsubChannel() + " id=" + entry.getId());
        }
        log.debug("[Security] Pub/Sub 广播安全事件 id={} type={} receivers={}",
                entry.getId(), entry.getEventType(), receiverCount);
    }
}
