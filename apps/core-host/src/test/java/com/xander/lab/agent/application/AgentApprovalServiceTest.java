package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.domain.AgentApproval;
import com.xander.lab.agent.domain.AgentRun;
import com.xander.lab.agent.domain.AgentToolInvocation;
import com.xander.lab.agent.infrastructure.AgentApprovalMapper;
import com.xander.lab.agent.infrastructure.AgentRunMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskExecutor;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Approval decisions resume the parent run with its original invocation identity. */
class AgentApprovalServiceTest {

    @Test
    void approvedDecisionResumesAndSchedulesThePersistedInvocation() {
        AgentApprovalMapper approvalMapper = mock(AgentApprovalMapper.class);
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        AgentRunLifecycle lifecycle = mock(AgentRunLifecycle.class);
        AgentConversationService conversationService = mock(AgentConversationService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentConversationService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(conversationService);
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        TaskExecutor executor = scheduled::set;
        AgentApproval approval = approval();
        AgentRun run = waitingRun();
        AgentToolInvocation invocation = invocation();
        when(approvalMapper.selectOne(any())).thenReturn(approval);
        when(approvalMapper.update(any(), any())).thenReturn(1);
        when(runMapper.selectById(4L)).thenReturn(run);
        when(journal.startApprovedInvocation(5L)).thenReturn(invocation);

        AgentApprovalService service = new AgentApprovalService(approvalMapper, runMapper, journal, lifecycle,
                new ObjectMapper(), executor, provider);
        AgentApproval result = service.decide(7L, 9L, 42L, true, "confirm");

        assertThat(result.getStatus()).isEqualTo(AgentApproval.STATUS_APPROVED);
        verify(lifecycle).resumeAfterApproval(eq(7L), eq(42L), eq(3L), eq(4L), any());
        assertThat(scheduled.get()).isNotNull();
        scheduled.get().run();
        verify(conversationService).resumeApprovedInvocation(approval, invocation);
        verify(journal, never()).completeInvocation(any(), any());
    }

    @Test
    void rejectedDecisionCancelsWithoutClaimingOrSchedulingTheTool() {
        AgentApprovalMapper approvalMapper = mock(AgentApprovalMapper.class);
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        AgentRunLifecycle lifecycle = mock(AgentRunLifecycle.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentConversationService> provider = mock(ObjectProvider.class);
        TaskExecutor executor = runnable -> {
            throw new AssertionError("rejected approval must not schedule work");
        };
        when(approvalMapper.selectOne(any())).thenReturn(approval());
        when(approvalMapper.update(any(), any())).thenReturn(1);
        when(runMapper.selectById(4L)).thenReturn(waitingRun());
        AgentApprovalService service = new AgentApprovalService(approvalMapper, runMapper, journal, lifecycle,
                new ObjectMapper(), executor, provider);

        service.decide(7L, 9L, 42L, false, "not now");

        verify(journal).rejectWaitingInvocation(5L, "用户拒绝审批");
        verify(journal, never()).startApprovedInvocation(any());
        verify(lifecycle).completeAfterApproval(eq(7L), eq(42L), eq(3L), eq(4L),
                eq("approval_rejected"), any());
        verify(lifecycle, never()).resumeAfterApproval(any(), any(), any(Long.class), any(), any());
    }

    @Test
    void rejectedWorkerSubmissionPersistsAResumedApprovalFailure() {
        AgentApprovalMapper approvalMapper = mock(AgentApprovalMapper.class);
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        AgentRunLifecycle lifecycle = mock(AgentRunLifecycle.class);
        AgentConversationService conversationService = mock(AgentConversationService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentConversationService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(conversationService);
        TaskExecutor executor = runnable -> {
            throw new org.springframework.core.task.TaskRejectedException("queue full");
        };
        AgentApproval approval = approval();
        AgentToolInvocation invocation = invocation();
        when(approvalMapper.selectOne(any())).thenReturn(approval);
        when(approvalMapper.update(any(), any())).thenReturn(1);
        when(runMapper.selectById(4L)).thenReturn(waitingRun());
        when(journal.startApprovedInvocation(5L)).thenReturn(invocation);
        AgentApprovalService service = new AgentApprovalService(approvalMapper, runMapper, journal, lifecycle,
                new ObjectMapper(), executor, provider);

        service.decide(7L, 9L, 42L, true, "confirm");

        verify(conversationService).failResumedApproval(eq(approval), eq(invocation),
                eq("审批恢复工作线程繁忙，请稍后重试"));
    }

    private AgentRun waitingRun() {
        AgentRun run = new AgentRun();
        run.setId(4L);
        run.setUserId(42L);
        run.setConversationId(7L);
        run.setRunVersion(3L);
        run.setStatus(AgentRun.STATUS_WAITING_APPROVAL);
        return run;
    }

    private AgentToolInvocation invocation() {
        AgentToolInvocation invocation = new AgentToolInvocation();
        invocation.setId(5L);
        invocation.setInvocationId("invocation-1");
        invocation.setToolName("publish_post");
        invocation.setArgumentsJson("{}");
        return invocation;
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
