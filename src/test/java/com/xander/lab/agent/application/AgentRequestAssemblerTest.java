package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.agent.domain.AgentAttachment;
import com.xander.lab.agent.domain.AgentMessage;
import com.xander.lab.agent.domain.AgentPlanItem;
import com.xander.lab.agent.infrastructure.AgentConversationProperties;
import com.xander.lab.agent.infrastructure.AgentModelProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Guards one-source request rendering and durable reference snapshots. */
class AgentRequestAssemblerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rendersTheModelRequestAndJournalSnapshotFromTheSameWindow() throws Exception {
        AgentConversationProperties conversationProperties = mock(AgentConversationProperties.class);
        when(conversationProperties.getContextMessageLimit()).thenReturn(5);
        AgentModelProperties modelProperties = new AgentModelProperties();
        modelProperties.setModel("agent-test-model");
        AgentPlanService planService = mock(AgentPlanService.class);
        when(planService.render(List.of(new AgentPlanItem("收集资料", "PENDING", ""))))
                .thenReturn("1. [PENDING] 收集资料");
        AgentRequestAssembler assembler = new AgentRequestAssembler(
                new AgentToolRegistry(List.of(tool("query_posts"), tool("publish_post"))),
                conversationProperties, modelProperties, planService, new AgentQuizService(objectMapper), objectMapper);
        List<AgentMessage> messages = List.of(
                message(1L, "user", "message", "first message outside window", List.of()),
                message(2L, "user", "message", "private draft request", List.of(
                        new AgentAttachment("https://cdn.example.com/a.png", "a.png", "image/png", 12))),
                message(3L, "assistant", "answer", "working on it", List.of()),
                message(4L, "tool", "tool_result", "{\"ok\":true}", List.of()),
                message(5L, "assistant", "thought", "next action", List.of()),
                message(6L, "user", "message", "continue", List.of()));

        AgentStepRequest request = assembler.assemble(new AgentRequestAssemblyInput(
                7L, 42L, 3L, 2, messages, List.of(new AgentPlanItem("收集资料", "PENDING", "")),
                "用户喜欢简洁答案", null, null, Set.of("publish_post", "query_posts"), 4, false));

        assertThat(request.context()).contains("private draft request").doesNotContain("first message outside window");
        assertThat(request.attachments()).extracting(AgentAttachment::url)
                .containsExactly("https://cdn.example.com/a.png");
        assertThat(request.contextMessageIds()).containsExactly(2L, 3L, 4L, 5L, 6L);
        assertThat(request.toolNames()).containsExactly("publish_post", "query_posts");
        assertThat(request.toolSchemaSetHash()).hasSize(64);
        assertThat(request.journalSnapshotJson()).doesNotContain("private draft request")
                .contains("contextMessageIds").contains("agent-test-model");
        var snapshot = objectMapper.readTree(request.journalSnapshotJson());
        assertThat(snapshot.path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(snapshot.path("attachments").get(0).path("url").asText())
                .isEqualTo("https://cdn.example.com/a.png");
    }

    private AgentMessage message(Long id, String role, String kind, String content,
                                 List<AgentAttachment> attachments) {
        AgentMessage message = new AgentMessage();
        message.setId(id);
        message.setRole(role);
        message.setKind(kind);
        message.setContent(content);
        message.setAttachments(attachments);
        return message;
    }

    private AgentTool tool(String name) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name + " description";
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode parametersSchema() {
                return JsonNodeFactory.instance.objectNode().put("type", "object");
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode execute(Long userId,
                                                                   com.fasterxml.jackson.databind.JsonNode args,
                                                                   ProgressListener onProgress) {
                return JsonNodeFactory.instance.objectNode();
            }
        };
    }
}
