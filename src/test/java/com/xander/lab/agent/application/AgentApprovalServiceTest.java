package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xander.lab.agent.domain.AgentApproval;
import com.xander.lab.agent.domain.AgentRun;
import com.xander.lab.agent.domain.AgentToolInvocation;
import com.xander.lab.agent.infrastructure.AgentApprovalMapper;
import com.xander.lab.agent.infrastructure.AgentRunMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Approval decisions execute the original invocation rather than creating a second tool call. */
class AgentApprovalServiceTest {

    @Test
    void approvedDecisionExecutesThePersistedInvocationId() {
        AgentApprovalMapper approvalMapper = mock(AgentApprovalMapper.class);
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        AgentRunLifecycle lifecycle = mock(AgentRunLifecycle.class);
        AgentEventService eventService = mock(AgentEventService.class);
        AgentTool tool = new AgentTool() {
            @Override public String name() { return "publish_post"; }
            @Override public String description() { return "publish"; }
            @Override public JsonNode parametersSchema() { return JsonNodeFactory.instance.objectNode(); }
            @Override public JsonNode execute(AgentToolContext context, JsonNode args, ProgressListener progress) {
                assertThat(context.invocationId()).isEqualTo("invocation-1");
                return JsonNodeFactory.instance.objectNode().put("ok", true);
            }
            @Override public JsonNode execute(Long userId, JsonNode args, ProgressListener progress) {
                throw new AssertionError("context execution expected");
            }
        };
        AgentApproval approval = approval();
        AgentRun run = new AgentRun();
        run.setId(4L);
        run.setUserId(42L);
        run.setConversationId(7L);
        run.setRunVersion(3L);
        run.setStatus(AgentRun.STATUS_WAITING_APPROVAL);
        AgentToolInvocation invocation = new AgentToolInvocation();
        invocation.setId(5L);
        invocation.setInvocationId("invocation-1");
        invocation.setToolName("publish_post");
        invocation.setArgumentsJson("{}");
        invocation.setStatus(AgentToolInvocation.STATUS_RUNNING);
        when(approvalMapper.selectOne(any())).thenReturn(approval);
        when(approvalMapper.update(any(), any())).thenReturn(1);
        when(runMapper.selectById(4L)).thenReturn(run);
        when(journal.startApprovedInvocation(5L)).thenReturn(invocation);

        AgentApprovalService service = new AgentApprovalService(approvalMapper, runMapper,
                new AgentToolRegistry(List.of(tool)), journal, lifecycle, eventService, new ObjectMapper());
        AgentApproval result = service.decide(7L, 9L, 42L, true, "confirm");

        assertThat(result.getStatus()).isEqualTo(AgentApproval.STATUS_APPROVED);
        verify(journal).completeInvocation(eq(invocation), any(JsonNode.class));
        verify(lifecycle).completeAfterApproval(eq(7L), eq(42L), eq(3L), eq(4L),
                eq("approval_executed"), any());
    }

    @Test
    void rejectedDecisionCancelsWithoutClaimingTheTool() {
        AgentApprovalMapper approvalMapper = mock(AgentApprovalMapper.class);
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        AgentRunLifecycle lifecycle = mock(AgentRunLifecycle.class);
        when(approvalMapper.selectOne(any())).thenReturn(approval());
        when(approvalMapper.update(any(), any())).thenReturn(1);
        AgentRun run = new AgentRun();
        run.setId(4L);
        run.setUserId(42L);
        run.setRunVersion(3L);
        run.setStatus(AgentRun.STATUS_WAITING_APPROVAL);
        when(runMapper.selectById(4L)).thenReturn(run);
        AgentApprovalService service = new AgentApprovalService(approvalMapper, runMapper,
                new AgentToolRegistry(List.of()), journal, lifecycle, mock(AgentEventService.class),
                new ObjectMapper());

        service.decide(7L, 9L, 42L, false, "not now");

        verify(journal).rejectWaitingInvocation(5L, "用户拒绝审批");
        verify(journal, org.mockito.Mockito.never()).startApprovedInvocation(any());
        verify(lifecycle).completeAfterApproval(eq(7L), eq(42L), eq(3L), eq(4L),
                eq("approval_rejected"), any());
    }

    private AgentApproval approval() {
        AgentApproval approval = new AgentApproval();
        approval.setId(9L);
        approval.setUserId(42L);
        approval.setConversationId(7L);
        approval.setRunId(4L);
        approval.setInvocationDbId(5L);
        approval.setInvocationId("invocation-1");
        approval.setToolName("publish_post");
        approval.setStatus(AgentApproval.STATUS_PENDING);
        return approval;
    }
}
