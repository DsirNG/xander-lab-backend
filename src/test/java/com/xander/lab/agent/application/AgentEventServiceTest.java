package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.agent.domain.AgentEvent;
import com.xander.lab.agent.infrastructure.AgentEventMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentEventServiceTest {

    @Mock
    private AgentEventMapper eventMapper;

    private AgentEventService service;

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AgentEvent.class);
    }

    @BeforeEach
    void setUp() {
        service = new AgentEventService(eventMapper, new ObjectMapper());
    }

    @Test
    void appendStoresEventAndDeliversItToLiveListener() {
        AtomicReference<AgentEvent> delivered = new AtomicReference<>();

        service.append(7L, "tool_start", JsonNodeFactory.instance.objectNode().put("tool", "blog_generate"),
                delivered::set);

        org.mockito.ArgumentCaptor<AgentEvent> captor =
                org.mockito.ArgumentCaptor.forClass(AgentEvent.class);
        verify(eventMapper).insert(captor.capture());
        AgentEvent stored = captor.getValue();
        assertThat(stored.getConversationId()).isEqualTo(7L);
        assertThat(stored.getEventType()).isEqualTo("tool_start");
        assertThat(stored.getDataContent()).contains("blog_generate");
        assertThat(delivered.get()).isSameAs(stored);
    }

    @Test
    void appendPersistsPlainStringDataVerbatim() {
        service.append(7L, "answer", "hello world", ignored -> { });

        org.mockito.ArgumentCaptor<AgentEvent> captor =
                org.mockito.ArgumentCaptor.forClass(AgentEvent.class);
        verify(eventMapper).insert(captor.capture());
        assertThat(captor.getValue().getDataContent()).isEqualTo("hello world");
    }

    @Test
    void listAfterReturnsOnlyNewerEvents() {
        AgentEvent event = new AgentEvent();
        event.setId(6L);
        when(eventMapper.selectList(any())).thenReturn(List.of(event));

        List<AgentEvent> events = service.listAfter(7L, 5L);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getId()).isEqualTo(6L);
    }

    @Test
    void clearDeletesAllEventsOfConversation() {
        service.clearConversationEvents(7L);

        org.mockito.ArgumentCaptor<LambdaQueryWrapper<AgentEvent>> captor =
                org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(eventMapper).delete(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("conversation_id");
    }

    @Test
    void appendKeepsWorkingWhenPersistenceFails() {
        when(eventMapper.insert(any())).thenThrow(new RuntimeException("db down"));
        AtomicReference<AgentEvent> delivered = new AtomicReference<>();

        service.append(7L, "answer", "still delivered", delivered::set);

        assertThat(delivered.get()).isNotNull();
        assertThat(delivered.get().getDataContent()).isEqualTo("still delivered");
    }
}