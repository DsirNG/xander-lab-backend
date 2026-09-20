package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.domain.AgentEvent;
import com.xander.lab.agent.infrastructure.AgentEventBusProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisAgentEventPubSubTest {

    private static final String CHANNEL = "test:agent-events";

    @Mock
    private StringRedisTemplate redisTemplate;

    private ObjectMapper objectMapper;
    private AgentEventBusProperties properties;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        properties = new AgentEventBusProperties();
        properties.setPubsubEnabled(true);
        properties.setPubsubChannel(CHANNEL);
    }

    @Test
    void broadcastsPersistedEventFieldsToTheConfiguredChannel() throws Exception {
        RedisAgentEventBroadcaster broadcaster =
                new RedisAgentEventBroadcaster(redisTemplate, objectMapper, properties);
        AgentEvent source = event(11L);
        String message = objectMapper.writeValueAsString(AgentEventPubSubMessage.from(source));
        when(redisTemplate.convertAndSend(CHANNEL, message)).thenReturn(1L);

        broadcaster.publish(source);

        verify(redisTemplate).convertAndSend(CHANNEL, message);
    }

    @Test
    void subscriberDeliversToLocalBusWithoutRebroadcasting() throws Exception {
        AgentEventBus bus = new AgentEventBus();
        List<AgentEvent> received = new ArrayList<>();
        bus.subscribe(7L, received::add);
        RedisAgentEventSubscriber subscriber = new RedisAgentEventSubscriber(objectMapper, bus);
        String raw = objectMapper.writeValueAsString(AgentEventPubSubMessage.from(event(11L)));
        Message redisMessage = mock(Message.class);
        when(redisMessage.getBody()).thenReturn(raw.getBytes(StandardCharsets.UTF_8));

        subscriber.onMessage(redisMessage, null);

        assertThat(received).hasSize(1);
        assertThat(received.get(0).getId()).isEqualTo(11L);
        assertThat(received.get(0).getConversationId()).isEqualTo(7L);
    }

    @Test
    void malformedMessageDoesNotKillTheListenerThread() {
        RedisAgentEventSubscriber subscriber = new RedisAgentEventSubscriber(objectMapper, new AgentEventBus());
        Message redisMessage = mock(Message.class);
        when(redisMessage.getBody()).thenReturn("not-json".getBytes(StandardCharsets.UTF_8));

        assertThatCode(() -> subscriber.onMessage(redisMessage, null))
                .doesNotThrowAnyException();
    }

    private AgentEvent event(long id) {
        AgentEvent event = new AgentEvent();
        event.setId(id);
        event.setConversationId(7L);
        event.setRunVersion(3L);
        event.setEventType("answer_delta");
        event.setDataContent("hello");
        return event;
    }
}
