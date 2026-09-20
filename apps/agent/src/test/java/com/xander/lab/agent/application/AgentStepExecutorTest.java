package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.agent.domain.AgentAttachment;
import com.xander.lab.agent.domain.AgentInputTurn;
import com.xander.lab.agent.domain.AgentStep;
import com.xander.lab.agent.infrastructure.AgentModelClient;
import com.xander.lab.common.ModelCallResult;
import com.xander.lab.common.ModelUsage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies that one model-call boundary consumes the immutable assembled request. */
class AgentStepExecutorTest {

    @Test
    void passesTheAssembledMultimodalRequestWithoutReassemblingIt() {
        AgentModelClient modelClient = mock(AgentModelClient.class);
        ModelCallResult expected = ModelCallResult.of(
                JsonNodeFactory.instance.objectNode().put("done", true), ModelUsage.NONE, "test-model");
        when(modelClient.decideNextStepStream(any(), any(), any(List.class), any(), any())).thenReturn(expected);
        AgentStepExecutor executor = AgentStepExecutor.legacy(modelClient);

        ModelCallResult actual = executor.execute(new AgentStep(), request(List.of(
                new AgentAttachment("https://cdn.example.com/a.png", "a.png", "image/png", 12))),
                ignored -> { }, () -> false);

        assertThat(actual).isSameAs(expected);
        verify(modelClient).decideNextStepStream(any(), any(), any(List.class), any(), any());
    }

    private AgentStepRequest request(List<AgentAttachment> attachments) {
        return new AgentStepRequest(7L, 42L, 3L, 1,
                List.of(AgentInputTurn.user("context")), "tools", attachments,
                List.of(10L), List.of("query_posts"), "hash", "agent-config:test",
                AgentRequestAssembler.INSTRUCTION_SET_VERSION, 4, false, "{}");
    }
}
