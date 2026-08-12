package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentMessage;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import com.xander.lab.agent.infrastructure.AgentConversationProperties;
import com.xander.lab.agent.infrastructure.AgentEventMapper;
import com.xander.lab.agent.infrastructure.AgentMessageMapper;
import com.xander.lab.agent.infrastructure.AgentModelClient;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentConversationServiceTest {

    @Mock
    private AgentConversationMapper conversationMapper;
    @Mock
    private AgentMessageMapper messageMapper;
    @Mock
    private AgentModelClient modelClient;
    @Mock
    private AgentConversationProperties properties;
    @Mock
    private TransactionTemplate transactionTemplate;

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    private AgentEventService eventService;

    private AgentToolRegistry registry;
    private AgentConversationService service;

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AgentConversation.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AgentMessage.class);
    }

    @BeforeEach
    void setUp() {
        registry = new AgentToolRegistry(List.of(new StubTool()));
        eventService = new AgentEventService(mock(AgentEventMapper.class), objectMapper);
        service = new AgentConversationService(
                conversationMapper, messageMapper, modelClient, registry, properties,
                transactionTemplate, eventService);
        org.mockito.Mockito.lenient().when(properties.getMaxSteps()).thenReturn(10);
        org.mockito.Mockito.lenient().when(properties.getMaxToolFailures()).thenReturn(2);
        org.mockito.Mockito.lenient().when(properties.getContextMessageLimit()).thenReturn(30);
        org.mockito.Mockito.lenient().when(properties.getStaleConversationMinutes()).thenReturn(30);
    }

    @Test
    void modelDecidesToCallToolThenFinish() {
        stubTransactions();
        ObjectNode first = decisionNode(false, "需要调用博客生成工具", "blog_generate", "帮我写博客");
        ObjectNode second = decisionNode(true, "文章已生成", null, null);
        second.put("reply", "已完成，博客文章已生成");
        when(modelClient.decideNextStep(any(), any())).thenReturn(first, second);

        AtomicInteger progressEvents = new AtomicInteger();
        AgentConversation ready = conversation("ready");
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(ready);

        AgentConversation result = service.executeTurn(7L, 42L, "帮我写一篇博客", (event, data) -> {
            if ("tool_progress".equals(event)) progressEvents.incrementAndGet();
        });

        assertThat(result.getStatus()).isEqualTo("ready");
        assertThat(progressEvents.get()).isEqualTo(1);
        verify(messageMapper).insert(argThatMessage("tool", "tool_call", "blog_generate"));
        verify(messageMapper).insert(argThatMessage("assistant", "answer", null));
    }

    @Test
    void toolFailureIsFedBackAndTerminatesAfterLimit() {
        stubTransactions();
        org.mockito.Mockito.when(properties.getMaxToolFailures()).thenReturn(1);
        ObjectNode failing = decisionNode(false, "调用工具", "failing_tool", null);
        when(modelClient.decideNextStep(any(), any())).thenReturn(failing);
        AgentToolRegistry failingRegistry = new AgentToolRegistry(
                List.of(new FailingTool("failing_tool")));
        service = new AgentConversationService(
                conversationMapper, messageMapper, modelClient, failingRegistry, properties,
                transactionTemplate, eventService);
        AgentConversation ready = conversation("ready");
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(ready);

        AgentConversation result = service.executeTurn(7L, 42L, "触发失败", (event, data) -> { });

        assertThat(result.getStatus()).isEqualTo("ready");
        verify(modelClient).decideNextStep(any(), any());
    }

    @Test
    void duplicateTurnWhileRunningIsRejected() {
        stubClaimTransaction();
        when(conversationMapper.update(any(), any())).thenReturn(0);
        AgentConversation running = conversation("running");
        when(conversationMapper.selectById(7L)).thenReturn(running);

        assertThatThrownBy(() -> service.executeTurn(7L, 42L, "再来一轮", (event, data) -> { }))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("执行中");
        org.mockito.Mockito.verifyNoInteractions(modelClient);
    }

    @Test
    void staleRunningConversationIsRestoredToReady() {
        AgentConversation stale = conversation("running");
        stale.setUpdatedAt(LocalDateTime.now().minusMinutes(31));
        AgentConversation recovered = conversation("ready");
        recovered.setErrorMessage("上一轮执行中断，可继续对话");
        when(conversationMapper.update(any(), any())).thenReturn(1);
        when(conversationMapper.selectById(7L)).thenReturn(recovered);

        AgentConversation result = service.resolveStale(stale, 42L);

        assertThat(result.getStatus()).isEqualTo("ready");
        assertThat(result.getErrorMessage()).contains("中断");
    }

    private AgentMessage argThatMessage(String role, String kind, String toolName) {
        return org.mockito.ArgumentMatchers.argThat(message ->
                role.equals(message.getRole())
                        && kind.equals(message.getKind())
                        && java.util.Objects.equals(toolName, message.getToolName()));
    }

    private void stubTransactions() {
        stubClaimTransaction();
        doAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any());
    }

    private void stubClaimTransaction() {
        doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private AgentConversation conversation(String status) {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(7L);
        conversation.setUserId(42L);
        conversation.setStatus(status);
        conversation.setUpdatedAt(LocalDateTime.now());
        return conversation;
    }

    private ObjectNode decisionNode(boolean done, String thought, String tool, String input) {
        ObjectNode decision = JsonNodeFactory.instance.objectNode();
        decision.put("done", done);
        decision.put("thought", thought);
        if (!done) {
            ObjectNode action = decision.putObject("action");
            action.put("tool", tool);
            if (input != null) {
                action.putObject("args").put("input", input);
            }
        }
        return decision;
    }

    /** A tool that succeeds and reports one progress event (name: blog_generate). */
    static class StubTool implements AgentTool {
        @Override
        public String name() { return "blog_generate"; }

        @Override
        public String description() { return "生成博客"; }

        @Override
        public com.fasterxml.jackson.databind.JsonNode parametersSchema() {
            return JsonNodeFactory.instance.objectNode();
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode execute(Long userId,
                                                               com.fasterxml.jackson.databind.JsonNode args,
                                                               ProgressListener onProgress) {
            onProgress.onProgress("开始生成");
            return JsonNodeFactory.instance.objectNode().put("ok", true);
        }
    }

    /** A tool that always throws (name must match the failing test). */
    static class FailingTool implements AgentTool {
        private final String nameValue;

        FailingTool(String nameValue) { this.nameValue = nameValue; }

        @Override
        public String name() { return nameValue; }

        @Override
        public String description() { return "总是失败的工具"; }

        @Override
        public com.fasterxml.jackson.databind.JsonNode parametersSchema() {
            return JsonNodeFactory.instance.objectNode();
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode execute(Long userId,
                                                               com.fasterxml.jackson.databind.JsonNode args,
                                                               ProgressListener onProgress) {
            throw new IllegalStateException("工具执行失败");
        }
    }
}