package com.xander.lab.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;

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
                7L, "ACCOUNT", "42", "ACCOUNT_DISABLED",
                "{\"accountId\":42,\"securityVersion\":3}")));

        subscriber.onMessage(message, null);

        assertThat(cache.isDisabled(42L)).isTrue();
    }

    @Test
    void ignoresMismatchedOrMalformedEvents() throws Exception {
        GatewaySecurityStateCache cache = new GatewaySecurityStateCache();
        GatewaySecurityEventSubscriber subscriber = new GatewaySecurityEventSubscriber(objectMapper, cache);
        Message mismatched = message(objectMapper.writeValueAsString(new GatewaySecurityEventMessage(
                7L, "ACCOUNT", "41", "ACCOUNT_DISABLED",
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
