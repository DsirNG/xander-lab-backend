package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.agent.domain.AgentStep;
import com.xander.lab.agent.domain.AgentToolInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies that pipeline validation happens before a tool invocation is durable or executable. */
class AgentToolExecutionPipelineTest {

    @Test
    void rejectsToolOutsideTheActiveSkillBeforeExecution() {
        AgentTool tool = tool("publish_post", "postId");
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool));
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        AgentToolExecutionPipeline pipeline = new AgentToolExecutionPipeline(registry, journal);

        assertThatThrownBy(() -> pipeline.execute(new AgentStep(), 42L, 7L, 3L, Set.of(),
                "publish_post", object("postId", 99), message -> { }, ignored -> { }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许");

        verify(journal, never()).startInvocation(any(), any(), any(), any());
    }

    @Test
    void rejectsMissingRequiredArgumentBeforeExecution() {
        AgentTool tool = tool("publish_post", "postId");
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool));
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        AgentToolExecutionPipeline pipeline = new AgentToolExecutionPipeline(registry, journal);

        assertThatThrownBy(() -> pipeline.execute(new AgentStep(), 42L, 7L, 3L, Set.of("publish_post"),
                "publish_post", JsonNodeFactory.instance.objectNode(), message -> { }, ignored -> { }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("postId");

        verify(journal, never()).startInvocation(any(), any(), any(), any());
    }

    @Test
    void recordsInvocationBeforeCallingTheTool() {
        AgentTool tool = tool("query_posts");
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool));
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        AgentToolInvocation invocation = new AgentToolInvocation();
        invocation.setId(12L);
        when(journal.startInvocation(any(), any(), any(), any())).thenReturn(invocation);
        AgentToolExecutionPipeline pipeline = new AgentToolExecutionPipeline(registry, journal);

        pipeline.execute(new AgentStep(), 42L, 7L, 3L, Set.of("query_posts"),
                "query_posts", JsonNodeFactory.instance.objectNode(), message -> { }, ignored -> { });

        verify(journal).startInvocation(any(), any(), any(), any());
        verify(journal).completeInvocation(any(), any());
    }

    private AgentTool tool(String name, String... required) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public JsonNode parametersSchema() {
                var schema = JsonNodeFactory.instance.objectNode().put("type", "object");
                var fields = schema.putObject("properties");
                for (String field : required) fields.putObject(field).put("type", "integer");
                var requiredNode = schema.putArray("required");
                for (String field : required) requiredNode.add(field);
                return schema;
            }
            @Override public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
                return JsonNodeFactory.instance.objectNode().put("ok", true);
            }
        };
    }

    private JsonNode object(String name, int value) {
        return JsonNodeFactory.instance.objectNode().put(name, value);
    }
}
