package com.xander.lab.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewaySecurityEventSubscriberTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void appliesMatchingAccountEvents() throws Exception {
        GatewaySecurityStateCache cache = new GatewaySecurityStateCache();
        GatewaySecurityEventSubscriber subscriber = new GatewaySecurityEventSubscriber(objectMapper, cache);
        Message message = message(objectMapper.writeValueAsString(new GatewaySecurityEventMessage(
                7L, "ACCOUNT", "42", "AccountDisabled",
                "{\"accountId\":42,\"securityVersion\":3,\"occurredAt\":\""
                        + LocalDateTime.now().minusNanos(50_000_000L) + "\"}")));

        subscriber.onMessage(message, null);

        assertThat(cache.isDisabled(42L)).isTrue();
        assertThat(cache.lastPropagationLatencyMillis()).isGreaterThanOrEqualTo(0L);

        Message enabled = message(objectMapper.writeValueAsString(new GatewaySecurityEventMessage(
                8L, "ACCOUNT", "42", "AccountEnabled",
                "{\"accountId\":42,\"securityVersion\":4}")));
        subscriber.onMessage(enabled, null);

        assertThat(cache.isDisabled(42L)).isFalse();
    }

    @Test
    void ignoresMismatchedOrMalformedEvents() throws Exception {
        GatewaySecurityStateCache cache = new GatewaySecurityStateCache();
        GatewaySecurityEventSubscriber subscriber = new GatewaySecurityEventSubscriber(objectMapper, cache);
        Message mismatched = message(objectMapper.writeValueAsString(new GatewaySecurityEventMessage(
                7L, "ACCOUNT", "41", "AccountDisabled",
                "{\"accountId\":42,\"securityVersion\":3}")));
        Message malformed = message("not-json");

        subscriber.onMessage(mismatched, null);
        subscriber.onMessage(malformed, null);

        assertThat(cache.disabledCount()).isZero();
    }

    private Message message(String body) {
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return message;
    }
}
