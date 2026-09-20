package com.xander.lab.agent.application;

import com.xander.lab.agent.domain.AgentEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentEventBusTest {

    @Mock
    private ObjectProvider<AgentEventBroadcaster> broadcasterProvider;

    @Test
    void durableEventIsDeliveredLocallyAndBroadcastAcrossInstances() {
        AgentEventBroadcaster broadcaster = mock(AgentEventBroadcaster.class);
        when(broadcasterProvider.getIfAvailable()).thenReturn(broadcaster);
        AgentEventBus bus = new AgentEventBus(broadcasterProvider);
        List<AgentEvent> received = new ArrayList<>();
        bus.subscribe(7L, received::add);
        AgentEvent event = event(11L);

        bus.publish(7L, event);

        assertThat(received).containsExactly(event);
        verify(broadcaster).publish(event);
    }

    @Test
    void idlessLiveEventStaysLocalToAvoidDuplicateAfterDurableReplay() {
        AgentEventBus bus = new AgentEventBus(broadcasterProvider);
        List<AgentEvent> received = new ArrayList<>();
        bus.subscribe(7L, received::add);
        AgentEvent live = event(null);

        bus.publish(7L, live);

        assertThat(received).containsExactly(live);
        verifyNoInteractions(broadcasterProvider);
    }

    @Test
    void crossInstanceFailureDoesNotBreakTheDurableProducerOrLocalSubscriber() {
        AgentEventBroadcaster broadcaster = mock(AgentEventBroadcaster.class);
        when(broadcasterProvider.getIfAvailable()).thenReturn(broadcaster);
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down"))
                .when(broadcaster).publish(org.mockito.ArgumentMatchers.any());
        AgentEventBus bus = new AgentEventBus(broadcasterProvider);
        List<AgentEvent> received = new ArrayList<>();
        bus.subscribe(7L, received::add);

        assertThatCode(() -> bus.publish(7L, event(11L)))
                .doesNotThrowAnyException();

        assertThat(received).hasSize(1);
        verify(broadcaster).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void subscriptionCanBeClosedWithoutLeavingTheConversationEntry() {
        AgentEventBus bus = new AgentEventBus();
        List<AgentEvent> received = new ArrayList<>();
        AgentEventBus.Subscription subscription = bus.subscribe(7L, received::add);
        subscription.close();

        bus.publish(7L, event(11L));

        assertThat(received).isEmpty();
    }

    private AgentEvent event(Long id) {
        AgentEvent event = new AgentEvent();
        event.setId(id);
        event.setConversationId(7L);
        event.setRunVersion(3L);
        event.setEventType("answer_delta");
        event.setDataContent("hello");
        return event;
    }
}
