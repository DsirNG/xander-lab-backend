package com.xander.lab.identity.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.common.security.ConnectionRegistry;
import com.xander.lab.common.security.SecurityStateCache;
import com.xander.lab.identity.domain.SecurityEventOutboxEntry;
import com.xander.lab.identity.infrastructure.SecurityEventOutboxMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisSecurityEventPubSubTest {

    private static final long ACCOUNT = 42L;
    private static final String CHANNEL = "test:security-events";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private SecurityEventOutboxMapper outboxMapper;

    private ObjectMapper objectMapper;
    private RefreshTokenProperties properties;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        properties = new RefreshTokenProperties();
        properties.setSecurityPubsubEnabled(true);
        properties.setSecurityPubsubChannel(CHANNEL);
    }

    @Test
    void broadcastsOnlyTheStableEventFieldsAndRequiresAListener() throws Exception {
        RedisSecurityEventBroadcaster broadcaster =
                new RedisSecurityEventBroadcaster(redisTemplate, objectMapper, properties);
        SecurityEventOutboxEntry source = event(7L, SecurityEventOutboxEntry.TYPE_ACCOUNT_DISABLED);
        when(redisTemplate.convertAndSend(CHANNEL, objectMapper.writeValueAsString(
                SecurityEventPubSubMessage.from(source)))).thenReturn(2L);

        broadcaster.publish(source);

        verify(redisTemplate).convertAndSend(CHANNEL, objectMapper.writeValueAsString(
                SecurityEventPubSubMessage.from(source)));
        assertThat(SecurityEventPubSubMessage.from(source).payload()).contains("securityVersion");
    }

    @Test
    void noRedisSubscriberKeepsTheOutboxEventRetryable() throws Exception {
        RedisSecurityEventBroadcaster broadcaster =
                new RedisSecurityEventBroadcaster(redisTemplate, objectMapper, properties);
        SecurityEventOutboxEntry source = event(7L, SecurityEventOutboxEntry.TYPE_ACCOUNT_DISABLED);
        when(redisTemplate.convertAndSend(CHANNEL, objectMapper.writeValueAsString(
                SecurityEventPubSubMessage.from(source)))).thenReturn(0L);

        assertThatThrownBy(() -> broadcaster.publish(source))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有订阅者");
    }

    @Test
    void subscriberAppliesLocallyWithoutRebroadcasting() throws Exception {
        SecurityStateCache cache = new SecurityStateCache();
        ConnectionRegistry registry = new ConnectionRegistry();
        SecurityEventOutboxPublisher publisher = new SecurityEventOutboxPublisher(
                outboxMapper,
                new SecurityStateApplier(cache, registry),
                properties,
                objectMapper);
        RedisSecurityEventSubscriber subscriber =
                new RedisSecurityEventSubscriber(objectMapper, publisher);
        SecurityEventPubSubMessage message = SecurityEventPubSubMessage.from(
                event(7L, SecurityEventOutboxEntry.TYPE_ACCOUNT_DISABLED));
        Message redisMessage = mock(Message.class);
        when(redisMessage.getBody()).thenReturn(objectMapper.writeValueAsString(message)
                .getBytes(StandardCharsets.UTF_8));

        subscriber.onMessage(redisMessage, null);

        assertThat(cache.isDisabled(ACCOUNT)).isTrue();
        verify(redisMessage).getBody();
    }

    @Test
    void malformedSubscriberMessageIsContainedForReconcilerFallback() {
        SecurityEventOutboxPublisher publisher = new SecurityEventOutboxPublisher(
                outboxMapper,
                new SecurityStateApplier(new SecurityStateCache(), new ConnectionRegistry()),
                properties,
                objectMapper);
        RedisSecurityEventSubscriber subscriber =
                new RedisSecurityEventSubscriber(objectMapper, publisher);
        Message redisMessage = mock(Message.class);
        when(redisMessage.getBody()).thenReturn("not-json".getBytes(StandardCharsets.UTF_8));

        assertThatCode(() -> subscriber.onMessage(redisMessage, null))
                .doesNotThrowAnyException();
    }

    private SecurityEventOutboxEntry event(long id, String eventType) {
        SecurityEventOutboxEntry entry = new SecurityEventOutboxEntry();
        entry.setId(id);
        entry.setAggregateType("ACCOUNT");
        entry.setAggregateId(String.valueOf(ACCOUNT));
        entry.setEventType(eventType);
        entry.setPayload("{\"accountId\":" + ACCOUNT + ",\"securityVersion\":7}");
        entry.setStatus(SecurityEventOutboxEntry.STATUS_PENDING);
        entry.setAttempts(0);
        return entry;
    }
}
