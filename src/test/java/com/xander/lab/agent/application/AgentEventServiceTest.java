package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentEvent;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import com.xander.lab.agent.infrastructure.AgentEventMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentEventServiceTest {
    @Mock private AgentEventMapper eventMapper;
    @Mock private AgentConversationMapper conversationMapper;
    private AgentEventService service;

    @BeforeAll
    static void metadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentEvent.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentConversation.class);
    }

    @BeforeEach
    void setUp() {
        service = new AgentEventService(eventMapper, conversationMapper, new ObjectMapper());
    }

    @Test
    void activeEventIsPersistedBeforeDeliveryAndCarriesRunVersion() {
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(eventMapper.insertForRun(any(), eq(42L), eq(true))).thenAnswer(invocation -> {
            AgentEvent event = invocation.getArgument(0);
            event.setId(9L);
            return 1;
        });
        AgentEvent event = service.storeActive(7L, 42L, 3L, "answer", "hello");

        assertThat(event.getId()).isEqualTo(9L);
        assertThat(event.getRunVersion()).isEqualTo(3L);
        assertThat(event.getDataContent()).isEqualTo("hello");
        verify(eventMapper).insertForRun(event, 42L, true);
    }

    @Test
    void structuredPayloadIsStillStoredAsJson() {
        when(eventMapper.insertForRun(any(), eq(42L), eq(false))).thenAnswer(invocation -> {
            AgentEvent event = invocation.getArgument(0);
            event.setId(10L);
            return 1;
        });

        AgentEvent event = service.storeForRun(
                7L, 42L, 3L, "complete", java.util.Map.of("status", "ready"), false);

        assertThat(event.getDataContent()).isEqualTo("{\"status\":\"ready\"}");
    }

    @Test
    void staleGenerationCannotPersistOrDeliverAnEvent() {
        when(conversationMapper.update(any(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.storeActive(7L, 42L, 2L, "answer", "late"))
                .isInstanceOf(AgentRunSupersededException.class);
    }

    @Test
    void replayAndTerminalQueriesAreScopedToRunVersion() {
        AgentEvent error = new AgentEvent();
        error.setId(10L);
        when(eventMapper.selectList(any())).thenReturn(List.of(error));

        assertThat(service.listAfter(7L, 3L, 8L)).containsExactly(error);
        assertThat(service.latestTerminal(7L, 3L)).isSameAs(error);
    }
}
