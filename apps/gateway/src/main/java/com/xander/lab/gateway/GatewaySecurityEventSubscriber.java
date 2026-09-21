package com.xander.lab.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.core.api.SecurityEventPubSubApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Gateway 安全事件快路径订阅者。
 *
 * <p>只应用到本地缓存，不重新广播。消息丢失、Redis 重启或 Gateway 重启由后续
 * 持久化 bootstrap/reconcile 负责恢复；Pub/Sub 本身不承担正确性。</p>
 */
@Component
@ConditionalOnProperty(prefix = "gateway.security-state", name = "pubsub-enabled", havingValue = "true")
public class GatewaySecurityEventSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(GatewaySecurityEventSubscriber.class);
    private final ObjectMapper objectMapper;
    private final GatewaySecurityStateCache securityStateCache;

    public GatewaySecurityEventSubscriber(ObjectMapper objectMapper,
                                          GatewaySecurityStateCache securityStateCache) {
        this.objectMapper = objectMapper;
        this.securityStateCache = securityStateCache;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String raw = new String(message.getBody(), StandardCharsets.UTF_8);
            SecurityEventPubSubApi.Message event = objectMapper.readValue(
                    raw, SecurityEventPubSubApi.Message.class);
            if (!SecurityEventPubSubApi.ACCOUNT_DISABLED.equals(event.eventType())
                    && !SecurityEventPubSubApi.ACCOUNT_ENABLED.equals(event.eventType())) {
                return;
            }
            apply(event);
        } catch (Exception ex) {
            // Pub/Sub 没有 ack/replay；监听线程不能因一条坏消息退出。
            log.error("[GatewaySecurity] Pub/Sub 安全事件处理失败，等待持久化 reconcile 兜底: {}",
                    ex.getMessage(), ex);
        }
    }

    private void apply(SecurityEventPubSubApi.Message event) throws Exception {
        if (event.aggregateId() == null || event.payload() == null) {
            throw new IllegalArgumentException("安全事件缺少 aggregateId 或 payload");
        }
        JsonNode payload = objectMapper.readTree(event.payload());
        JsonNode accountId = payload.get("accountId");
        JsonNode version = payload.get("securityVersion");
        if (accountId == null || !accountId.canConvertToLong()
                || !String.valueOf(accountId.asLong()).equals(event.aggregateId())) {
            throw new IllegalArgumentException("安全事件 accountId 与 aggregateId 不一致");
        }
        long securityVersion = version != null && version.canConvertToLong()
                ? version.asLong() : 0L;
        securityStateCache.apply(accountId.asLong(),
                SecurityEventPubSubApi.ACCOUNT_DISABLED.equals(event.eventType()),
                securityVersion, epochMillis(payload.get("occurredAt")));
    }

    private long epochMillis(JsonNode value) {
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            return 0L;
        }
        try {
            return LocalDateTime.parse(value.asText()).atZone(ZoneId.systemDefault())
                    .toInstant().toEpochMilli();
        } catch (DateTimeException ex) {
            log.debug("[GatewaySecurity] occurredAt 无法解析，忽略延迟观测字段 value={}",
                    value.asText());
            return 0L;
        }
    }
}
