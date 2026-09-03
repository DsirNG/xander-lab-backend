package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentEvent;
import com.xander.lab.agent.domain.AgentMessage;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import com.xander.lab.agent.infrastructure.AgentMessageMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies that extracted event projection keeps the legacy message and event contract. */
@ExtendWith(MockitoExtension.class)
class AgentEventProjectorTest {
    @Mock
    private AgentConversationMapper conversationMapper;
    @Mock
    private AgentMessageMapper messageMapper;
    @Mock
    private AgentEventService eventService;
    @Mock
    private TransactionTemplate transactionTemplate;

    private AgentEventProjector projector;

    @BeforeAll
    static void initializeMybatisMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), AgentConversation.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), AgentMessage.class);
    }

    @BeforeEach
    void setUp() {
        projector = new AgentEventProjector(conversationMapper, messageMapper, eventService, transactionTemplate);
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void activeMessageIsPersistedOnlyForTheCurrentRunningGeneration() {
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(messageMapper.insertForActiveRun(any(), eq(42L), eq(3L))).thenReturn(1);

        projector.saveActiveMessage(7L, 42L, 3L, "assistant", "answer", null, "完成");

        verify(messageMapper).insertForActiveRun(org.mockito.ArgumentMatchers.argThat(message ->
                message.getConversationId().equals(7L)
                        && message.getRunVersion().equals(3L)
                        && "assistant".equals(message.getRole())
                        && "answer".equals(message.getKind())
                        && "完成".equals(message.getContent())), eq(42L), eq(3L));
    }

    @Test
    void firstPlanProjectionCreatesTheLegacyPlanMessage() {
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(messageMapper.update(any(), any())).thenReturn(0);
        when(messageMapper.insertForActiveRun(any(), eq(42L), eq(3L))).thenReturn(1);

        projector.saveOrUpdateActivePlanMessage(7L, 42L, 3L, "[{\"title\":\"查资料\"}]");

        verify(messageMapper).insertForActiveRun(org.mockito.ArgumentMatchers.argThat(message ->
                "assistant".equals(message.getRole())
                        && "plan".equals(message.getKind())
                        && message.getRunVersion().equals(3L)
                        && message.getContent().contains("查资料")), eq(42L), eq(3L));
    }

    @Test
    void activeEventIsPersistedThenDeliveredToTheExistingListener() {
        AgentEvent event = new AgentEvent();
        event.setId(9L);
        when(eventService.storeActive(7L, 42L, 3L, "answer", "完成")).thenReturn(event);
        AtomicReference<AgentEvent> received = new AtomicReference<>();
        Consumer<AgentEvent> listener = received::set;

        AgentEvent projected = projector.appendActive(7L, 42L, 3L, "answer", "完成", listener);

        assertThat(projected).isSameAs(event);
        verify(eventService).deliver(event, listener);
    }

    @Test
    void toolProgressPayloadKeepsTheOptionalStageDelimiterContract() {
        JsonNode payload = projector.progressPayload("blog_generate", "draft|正在生成");

        assertThat(payload.path("tool").asText()).isEqualTo("blog_generate");
        assertThat(payload.path("stage").asText()).isEqualTo("draft");
        assertThat(payload.path("message").asText()).isEqualTo("正在生成");
    }
}
