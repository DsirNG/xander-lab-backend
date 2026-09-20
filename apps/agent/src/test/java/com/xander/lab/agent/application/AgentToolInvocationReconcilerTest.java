package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.agent.domain.AgentRun;
import com.xander.lab.agent.domain.AgentToolInvocation;
import com.xander.lab.agent.infrastructure.AgentRunMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies that crash recovery settles only a result proven by the owning tool. */
class AgentToolInvocationReconcilerTest {

    @Test
    void settlesUnknownInvocationOnlyWhenToolProvesDurableEffect() {
        AgentTool tool = new AgentTool() {
            @Override public String name() { return "reconciled_tool"; }
            @Override public String description() { return "test"; }
            @Override public JsonNode parametersSchema() { return JsonNodeFactory.instance.objectNode(); }
            @Override public JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress) {
                throw new UnsupportedOperationException();
            }
            @Override public Optional<JsonNode> reconcile(AgentToolContext context, JsonNode args) {
                return Optional.of(JsonNodeFactory.instance.objectNode().put("resourceId", 12));
            }
        };
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentToolInvocation invocation = new AgentToolInvocation();
        invocation.setId(6L);
        invocation.setRunId(5L);
        invocation.setInvocationId("invocation-1");
        invocation.setToolName("reconciled_tool");
        invocation.setArgumentsJson("{}");
        AgentRun run = new AgentRun();
        run.setUserId(42L);
        run.setConversationId(3L);
        run.setRunVersion(9L);
        when(journal.unknownInvocations(any(Integer.class))).thenReturn(List.of(invocation));
        when(runMapper.selectById(5L)).thenReturn(run);

        new AgentToolInvocationReconciler(journal, new AgentToolRegistry(List.of(tool)), runMapper,
                new ObjectMapper()).reconcileUnknownInvocations();

        verify(journal).reconcileInvocation(any(AgentToolInvocation.class), any(JsonNode.class));
    }
}
