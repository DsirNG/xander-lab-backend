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

    /**
     * 审批期间参数被改动过的动作，不能按原样执行。
     *
     * <p>{@code agent_approval.request_fingerprint} 的列注释承诺了"变更操作必须重新审批"。
     * 在这条路径接上之前，那一列**被计算、落库、然后从未被读取**——一个承诺了却不存在的机制，
     * 比没有注释更危险：以后有人加"重试这条审批"的路径时会以为校验已经在了。本用例把那个承诺
     * 钉成真机制。</p>
     */
    @Test
    void approvalWhoseRecordedArgumentsChangedIsRejectedInsteadOfExecuted() {
        AgentApprovalMapper approvalMapper = mock(AgentApprovalMapper.class);
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        AgentRunLifecycle lifecycle = mock(AgentRunLifecycle.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentConversationService> provider = mock(ObjectProvider.class);
        TaskExecutor executor = runnable -> {
            throw new AssertionError("a drifted approval must not schedule work");
        };
        AgentApproval approval = approval();
        // 用户批准的是 postId=7，而现在这条 invocation 记录的是 postId=8。
        approval.setRequestFingerprint(AgentArgsFingerprint.of("publish_post", json("{\"postId\":7}")));
        AgentToolInvocation invocation = invocation();
        invocation.setArgumentsJson("{\"postId\":8}");
        invocation.setEffectId("effect-x");
        when(approvalMapper.selectOne(any())).thenReturn(approval);
        when(approvalMapper.update(any(), any())).thenReturn(1);
        when(runMapper.selectById(4L)).thenReturn(waitingRun());
        when(journal.findInvocation(5L)).thenReturn(java.util.Optional.of(invocation));

        AgentApprovalService service = new AgentApprovalService(approvalMapper, runMapper, journal, lifecycle,
                new ObjectMapper(), executor, provider);
        AgentApproval result = service.decide(7L, 9L, 42L, true, "confirm");

        assertThat(result.getStatus()).isEqualTo(AgentApproval.STATUS_REJECTED);
        assertThat(result.getDecisionReason()).contains("参数");
        verify(journal).rejectWaitingInvocation(5L, "用户拒绝审批");
        verify(journal, never()).startApprovedInvocation(any());
        verify(lifecycle, never()).resumeAfterApproval(any(), any(), any(Long.class), any(), any());
    }

    @Test
    void approvalWhoseArgumentsStillMatchIsExecuted() {
        AgentApprovalMapper approvalMapper = mock(AgentApprovalMapper.class);
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        AgentRunLifecycle lifecycle = mock(AgentRunLifecycle.class);
        AgentConversationService conversationService = mock(AgentConversationService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentConversationService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(conversationService);
        TaskExecutor executor = runnable -> { };
        AgentApproval approval = approval();
        approval.setRequestFingerprint(AgentArgsFingerprint.of("publish_post", json("{\"postId\":7}")));
        AgentToolInvocation invocation = invocation();
        // 字段顺序与落库时不同——归一化必须让它们相等，否则每个审批都会误判成漂移。
        invocation.setArgumentsJson("{\"postId\":7}");
        when(approvalMapper.selectOne(any())).thenReturn(approval);
        when(approvalMapper.update(any(), any())).thenReturn(1);
        when(runMapper.selectById(4L)).thenReturn(waitingRun());
        when(journal.findInvocation(5L)).thenReturn(java.util.Optional.of(invocation));
        when(journal.startApprovedInvocation(5L)).thenReturn(invocation);

        AgentApprovalService service = new AgentApprovalService(approvalMapper, runMapper, journal, lifecycle,
                new ObjectMapper(), executor, provider);
        AgentApproval result = service.decide(7L, 9L, 42L, true, "confirm");

        assertThat(result.getStatus()).isEqualTo(AgentApproval.STATUS_APPROVED);
        verify(journal).startApprovedInvocation(5L);
    }

    /**
     * 迁移前落库的待决审批用的是旧指纹算法，必须仍然放行。
     *
     * <p>不放行的话，一次部署会把恰好挂在半空的审批全部作废，用户被锁在自己的会话里——
     * 正是 B2 修掉的那一类故障。这里在审批这一层再钉一次，因为兼容分支写在
     * {@link AgentArgsFingerprint#matches} 里，而它是否**被接上**是另一个问题。</p>
     */
    @Test
    void approvalWhoseFingerprintWasWrittenByTheLegacyAlgorithmIsStillExecuted() {
        AgentApprovalMapper approvalMapper = mock(AgentApprovalMapper.class);
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        AgentRunLifecycle lifecycle = mock(AgentRunLifecycle.class);
        AgentConversationService conversationService = mock(AgentConversationService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentConversationService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(conversationService);
        TaskExecutor executor = runnable -> { };
        AgentApproval approval = approval();
        approval.setRequestFingerprint(
                AgentArgsFingerprint.legacyOf("publish_post", json("{\"postId\":7}")));
        AgentToolInvocation invocation = invocation();
        invocation.setArgumentsJson("{\"postId\":7}");
        when(approvalMapper.selectOne(any())).thenReturn(approval);
        when(approvalMapper.update(any(), any())).thenReturn(1);
        when(runMapper.selectById(4L)).thenReturn(waitingRun());
        when(journal.findInvocation(5L)).thenReturn(java.util.Optional.of(invocation));
        when(journal.startApprovedInvocation(5L)).thenReturn(invocation);

        AgentApprovalService service = new AgentApprovalService(approvalMapper, runMapper, journal, lifecycle,
                new ObjectMapper(), executor, provider);
        AgentApproval result = service.decide(7L, 9L, 42L, true, "confirm");

        assertThat(result.getStatus()).isEqualTo(AgentApproval.STATUS_APPROVED);
        verify(journal).startApprovedInvocation(5L);
    }

    /**
     * 读不到 invocation 记录不是"参数变了"。
     *
     * <p>那两种情况必须分开：记录没了是"这条审批已经失去执行对象"，由 {@code startApprovedInvocation}
     * 报出来；参数变了才是"不能按原样执行"。把前者当成漂移，会让一次级联删除顺手作废用户的审批决定，
     * 而错误信息还会说"参数已变更"——排查方向从一开始就是错的。</p>
     */
    @Test
    void aMissingInvocationRecordIsNotTreatedAsArgumentDrift() {
        AgentApprovalMapper approvalMapper = mock(AgentApprovalMapper.class);
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        AgentRunLifecycle lifecycle = mock(AgentRunLifecycle.class);
        AgentConversationService conversationService = mock(AgentConversationService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentConversationService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(conversationService);
        TaskExecutor executor = runnable -> { };
        AgentApproval approval = approval();
        approval.setRequestFingerprint(AgentArgsFingerprint.of("publish_post", json("{\"postId\":7}")));
        when(approvalMapper.selectOne(any())).thenReturn(approval);
        when(approvalMapper.update(any(), any())).thenReturn(1);
        when(runMapper.selectById(4L)).thenReturn(waitingRun());
        when(journal.findInvocation(5L)).thenReturn(java.util.Optional.empty());
        when(journal.startApprovedInvocation(5L)).thenReturn(invocation());

        AgentApprovalService service = new AgentApprovalService(approvalMapper, runMapper, journal, lifecycle,
                new ObjectMapper(), executor, provider);
        AgentApproval result = service.decide(7L, 9L, 42L, true, "confirm");

        assertThat(result.getStatus()).isEqualTo(AgentApproval.STATUS_APPROVED);
        // 决定理由必须是调用方给的那个，而不是漂移话术——漂移话术里含"参数"。
        assertThat(result.getDecisionReason()).isEqualTo("confirm").doesNotContain("参数");
    }

    /**
     * 拒绝审批必须把那个业务动作一并作废。
     *
     * <p>只把 invocation 标成 CANCELLED 是不够的：Effect 还留着 {@code OPEN}，模型下一次重发同样的
     * 调用会采纳它——那等于绕过了刚刚被拒绝的这次审批。</p>
     */
    @Test
    void rejectingAnApprovalAbandonsItsBusinessEffect() {
        AgentApprovalMapper approvalMapper = mock(AgentApprovalMapper.class);
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentExecutionJournal journal = mock(AgentExecutionJournal.class);
        AgentRunLifecycle lifecycle = mock(AgentRunLifecycle.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentConversationService> provider = mock(ObjectProvider.class);
        TaskExecutor executor = runnable -> {
            throw new AssertionError("rejected approval must not schedule work");
        };
        AgentToolInvocation invocation = invocation();
        invocation.setEffectId("effect-x");
        when(approvalMapper.selectOne(any())).thenReturn(approval());
        when(approvalMapper.update(any(), any())).thenReturn(1);
        when(runMapper.selectById(4L)).thenReturn(waitingRun());
        when(journal.findInvocation(5L)).thenReturn(java.util.Optional.of(invocation));

        AgentApprovalService service = new AgentApprovalService(approvalMapper, runMapper, journal, lifecycle,
                new ObjectMapper(), executor, provider);
        service.decide(7L, 9L, 42L, false, "not now");

        verify(journal).abandonEffect("effect-x");
    }

    @Test
    void rejectingAnApprovalWithoutAnInvocationRecordDoesNotBlameAnEffect() {
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
        when(journal.findInvocation(5L)).thenReturn(java.util.Optional.empty());

        AgentApprovalService service = new AgentApprovalService(approvalMapper, runMapper, journal, lifecycle,
                new ObjectMapper(), executor, provider);
        service.decide(7L, 9L, 42L, false, "not now");

        verify(journal, never()).abandonEffect(any());
    }

    private com.fasterxml.jackson.databind.JsonNode json(String text) {
        try {
            return new ObjectMapper().readTree(text);
        } catch (Exception exception) {
            throw new IllegalStateException("测试里的 JSON 字面量不合法：" + text, exception);
        }
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
