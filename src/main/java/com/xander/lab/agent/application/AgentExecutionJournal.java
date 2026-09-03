package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.xander.lab.agent.domain.AgentRun;
import com.xander.lab.agent.domain.AgentStep;
import com.xander.lab.agent.domain.AgentToolInvocation;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import com.xander.lab.agent.infrastructure.AgentRunMapper;
import com.xander.lab.agent.infrastructure.AgentStepMapper;
import com.xander.lab.agent.infrastructure.AgentToolInvocationMapper;
import com.xander.lab.common.ModelCallResult;
import com.xander.lab.common.ModelUsage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Writes the execution facts around the existing conversation loop.
 *
 * <p>Phase 1 deliberately records minimal request metadata instead of raw prompts. This
 * preserves the current API while creating stable run, decision, and tool boundaries for
 * the later request assembler and tool pipeline.</p>
 */
@Service
public class AgentExecutionJournal {
    private final AgentConversationMapper conversationMapper;
    private final AgentRunMapper runMapper;
    private final AgentStepMapper stepMapper;
    private final AgentToolInvocationMapper invocationMapper;
    private final boolean enabled;

    private AgentExecutionJournal() {
        this.conversationMapper = null;
        this.runMapper = null;
        this.stepMapper = null;
        this.invocationMapper = null;
        this.enabled = false;
    }

    /** Compatibility instance for focused legacy loop tests that do not load persistence mappers. */
    static AgentExecutionJournal disabled() {
        return new AgentExecutionJournal();
    }

    @Autowired
    public AgentExecutionJournal(AgentConversationMapper conversationMapper, AgentRunMapper runMapper,
                                 AgentStepMapper stepMapper, AgentToolInvocationMapper invocationMapper) {
        this.conversationMapper = conversationMapper;
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.invocationMapper = invocationMapper;
        this.enabled = true;
    }

    /** Creates one run exactly after the conversation generation has been claimed. */
    @Transactional
    public void startRun(Long conversationId, Long userId, long runVersion) {
        if (!enabled) return;
        AgentRun existing = findRun(conversationId, runVersion);
        if (existing != null) return;
        requireActiveConversation(conversationId, userId, runVersion);
        LocalDateTime now = LocalDateTime.now();
        AgentRun run = new AgentRun();
        run.setUserId(userId);
        run.setConversationId(conversationId);
        run.setRunVersion(runVersion);
        run.setStatus(AgentRun.STATUS_RUNNING);
        run.setStartedAt(now);
        if (runMapper.insert(run) != 1) {
            throw new IllegalStateException("智能体运行日志创建失败");
        }
    }

    /** Persists the resolved intent and skill capability snapshot for this run. */
    @Transactional
    public void recordSkillSelection(Long conversationId, Long userId, long runVersion,
                                     AgentSkillSelection selection) {
        if (!enabled || selection == null) return;
        AgentRun run = findRun(conversationId, runVersion);
        if (run == null) throw new AgentRunSupersededException(conversationId, runVersion);
        int changed = runMapper.update(null, new LambdaUpdateWrapper<AgentRun>()
                .eq(AgentRun::getId, run.getId())
                .eq(AgentRun::getUserId, userId)
                .eq(AgentRun::getStatus, AgentRun.STATUS_RUNNING)
                .set(AgentRun::getIntentJson, selection.intentJson())
                .set(AgentRun::getSkillsJson, selection.skillsJson())
                .set(AgentRun::getExecutionSnapshotJson, selection.executionSnapshotJson()));
        if (changed != 1) throw new AgentRunSupersededException(conversationId, runVersion);
    }

    /** Writes a model-step intent before the remote model call starts. */
    @Transactional
    public AgentStep startStep(Long conversationId, Long userId, long runVersion, int stepNo,
                               String requestSnapshotJson) {
        if (!enabled) return disabledStep(stepNo);
        AgentRun run = requireRunningRun(conversationId, userId, runVersion);
        AgentStep step = new AgentStep();
        step.setRunId(run.getId());
        step.setStepNo(stepNo);
        step.setStatus(AgentStep.STATUS_RUNNING);
        step.setRequestSnapshotJson(requestSnapshotJson);
        step.setStartedAt(LocalDateTime.now());
        if (stepMapper.insert(step) != 1) {
            throw new IllegalStateException("智能体步骤日志创建失败");
        }
        return step;
    }

    /** Writes a fully assembled request's durable reference snapshot before the model call. */
    public AgentStep startStep(AgentStepRequest request) {
        return startStep(request.conversationId(), request.userId(), request.runVersion(), request.stepNo(),
                request.journalSnapshotJson());
    }

    /** Saves parsed model output and usage after a successful remote call. */
    @Transactional
    public void completeStep(AgentStep step, ModelCallResult call) {
        if (!enabled || step == null || step.getId() == null) return;
        ModelUsage usage = call.usage() == null ? ModelUsage.NONE : call.usage();
        int changed = stepMapper.update(null, new LambdaUpdateWrapper<AgentStep>()
                .eq(AgentStep::getId, step.getId())
                .eq(AgentStep::getStatus, AgentStep.STATUS_RUNNING)
                .set(AgentStep::getStatus, AgentStep.STATUS_COMPLETED)
                .set(AgentStep::getDecisionJson, call.payload() == null ? null : call.payload().toString())
                .set(AgentStep::getModelName, call.model())
                .set(AgentStep::getInputTokens, usage.inputTokens())
                .set(AgentStep::getCachedTokens, usage.cachedTokens())
                .set(AgentStep::getOutputTokens, usage.outputTokens())
                .set(AgentStep::getCompletedAt, LocalDateTime.now()));
        if (changed != 1) throw new IllegalStateException("智能体步骤日志状态已变化");
    }

    @Transactional
    public void failStep(AgentStep step, String reason) {
        transitionStep(step, AgentStep.STATUS_FAILED, reason);
    }

    @Transactional
    public void cancelStep(AgentStep step) {
        transitionStep(step, AgentStep.STATUS_CANCELLED, "用户取消");
    }

    /** Persists the invocation identity before any tool side effect can begin. */
    @Transactional
    public AgentToolInvocation startInvocation(AgentStep step, String invocationId, String toolName,
                                               JsonNode arguments) {
        if (!enabled) return disabledInvocation(invocationId, toolName);
        AgentToolInvocation invocation = new AgentToolInvocation();
        invocation.setRunId(step.getRunId());
        invocation.setStepId(step.getId());
        invocation.setInvocationId(invocationId);
        invocation.setToolName(toolName);
        invocation.setArgumentsJson(arguments == null ? null : arguments.toString());
        invocation.setStatus(AgentToolInvocation.STATUS_RUNNING);
        invocation.setStartedAt(LocalDateTime.now());
        if (invocationMapper.insert(invocation) != 1) {
            throw new IllegalStateException("智能体工具调用日志创建失败");
        }
        return invocation;
    }

    @Transactional
    public void completeInvocation(AgentToolInvocation invocation, JsonNode result) {
        transitionInvocation(invocation, AgentToolInvocation.STATUS_SUCCEEDED,
                result == null ? null : result.toString(), null);
    }

    @Transactional
    public void failInvocation(AgentToolInvocation invocation, String reason) {
        transitionInvocation(invocation, AgentToolInvocation.STATUS_FAILED, null, reason);
    }

    @Transactional
    public void cancelInvocation(AgentToolInvocation invocation) {
        transitionInvocation(invocation, AgentToolInvocation.STATUS_CANCELLED, null, "用户取消");
    }

    /** Moves a durable invocation into the non-executing approval wait state. */
    @Transactional
    public void waitForApproval(AgentToolInvocation invocation) {
        if (!enabled || invocation == null || invocation.getId() == null) return;
        int changed = invocationMapper.update(null, new LambdaUpdateWrapper<AgentToolInvocation>()
                .eq(AgentToolInvocation::getId, invocation.getId())
                .eq(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_RUNNING)
                .set(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_WAITING_APPROVAL));
        if (changed != 1) throw new IllegalStateException("工具调用审批状态已变化");
        invocation.setStatus(AgentToolInvocation.STATUS_WAITING_APPROVAL);
    }

    /** Pauses the durable run after its invocation is safely waiting for a user decision. */
    @Transactional
    public void waitRunForApproval(Long conversationId, Long userId, long runVersion) {
        if (!enabled) return;
        AgentRun run = findRun(conversationId, runVersion);
        if (run == null) throw new AgentRunSupersededException(conversationId, runVersion);
        int changed = runMapper.update(null, new LambdaUpdateWrapper<AgentRun>()
                .eq(AgentRun::getId, run.getId())
                .eq(AgentRun::getUserId, userId)
                .eq(AgentRun::getStatus, AgentRun.STATUS_RUNNING)
                .set(AgentRun::getStatus, AgentRun.STATUS_WAITING_APPROVAL));
        if (changed != 1) throw new AgentRunSupersededException(conversationId, runVersion);
    }

    /** Claims an approved invocation exactly once before its side effect begins. */
    @Transactional
    public AgentToolInvocation startApprovedInvocation(Long invocationDbId) {
        if (!enabled) throw new IllegalStateException("审批执行需要启用执行日志");
        AgentToolInvocation invocation = invocationMapper.selectById(invocationDbId);
        if (invocation == null) throw new IllegalStateException("审批关联的工具调用不存在");
        int changed = invocationMapper.update(null, new LambdaUpdateWrapper<AgentToolInvocation>()
                .eq(AgentToolInvocation::getId, invocationDbId)
                .eq(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_WAITING_APPROVAL)
                .set(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_RUNNING));
        if (changed != 1) throw new IllegalStateException("工具调用审批状态已变化");
        invocation.setStatus(AgentToolInvocation.STATUS_RUNNING);
        return invocation;
    }

    /** Cancels a still-pending invocation after its owner rejects the exact requested action. */
    @Transactional
    public void rejectWaitingInvocation(Long invocationDbId, String reason) {
        if (!enabled) return;
        int changed = invocationMapper.update(null, new LambdaUpdateWrapper<AgentToolInvocation>()
                .eq(AgentToolInvocation::getId, invocationDbId)
                .eq(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_WAITING_APPROVAL)
                .set(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_CANCELLED)
                .set(AgentToolInvocation::getErrorMessage, limit(reason, 1000))
                .set(AgentToolInvocation::getCompletedAt, LocalDateTime.now()));
        if (changed != 1) throw new IllegalStateException("工具调用审批状态已变化");
    }

    /** Completes a run that was deliberately paused for approval rather than actively executing. */
    @Transactional
    public void completeWaitingRun(Long runId, Long userId) {
        if (!enabled) return;
        int changed = runMapper.update(null, new LambdaUpdateWrapper<AgentRun>()
                .eq(AgentRun::getId, runId)
                .eq(AgentRun::getUserId, userId)
                .eq(AgentRun::getStatus, AgentRun.STATUS_WAITING_APPROVAL)
                .set(AgentRun::getStatus, AgentRun.STATUS_COMPLETED)
                .set(AgentRun::getCompletedAt, LocalDateTime.now()));
        if (changed != 1) throw new IllegalStateException("智能体审批运行状态已变化");
    }

    /** Fails a run that was paused for approval after its approved tool failed. */
    @Transactional
    public void failWaitingRun(Long runId, Long userId, String reason) {
        if (!enabled) return;
        int changed = runMapper.update(null, new LambdaUpdateWrapper<AgentRun>()
                .eq(AgentRun::getId, runId)
                .eq(AgentRun::getUserId, userId)
                .eq(AgentRun::getStatus, AgentRun.STATUS_WAITING_APPROVAL)
                .set(AgentRun::getStatus, AgentRun.STATUS_FAILED)
                .set(AgentRun::getFailureReason, limit(reason, 1000))
                .set(AgentRun::getCompletedAt, LocalDateTime.now()));
        if (changed != 1) throw new IllegalStateException("智能体审批运行状态已变化");
    }

    /** Returns a bounded batch of interrupted tool calls for read-only business reconciliation. */
    @Transactional(readOnly = true)
    public List<AgentToolInvocation> unknownInvocations(int batchSize) {
        if (!enabled) return List.of();
        return invocationMapper.selectList(new LambdaQueryWrapper<AgentToolInvocation>()
                .eq(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_UNKNOWN)
                .orderByAsc(AgentToolInvocation::getUpdatedAt)
                .last("LIMIT " + Math.min(Math.max(batchSize, 1), 100)));
    }

    /** Finds approval-resumed calls whose worker disappeared before it could settle an outcome. */
    @Transactional(readOnly = true)
    public List<AgentToolInvocation> runningInvocationsBefore(LocalDateTime cutoff, int batchSize) {
        if (!enabled) return List.of();
        return invocationMapper.selectList(new LambdaQueryWrapper<AgentToolInvocation>()
                .eq(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_RUNNING)
                .le(AgentToolInvocation::getUpdatedAt, cutoff)
                .orderByAsc(AgentToolInvocation::getUpdatedAt)
                .last("LIMIT " + Math.min(Math.max(batchSize, 1), 100)));
    }

    /** Converts only an expired approved execution into an explicit reconciliation state. */
    @Transactional
    public void markInvocationUnknown(AgentToolInvocation invocation, String reason) {
        if (!enabled || invocation == null || invocation.getId() == null) return;
        invocationMapper.update(null, new LambdaUpdateWrapper<AgentToolInvocation>()
                .eq(AgentToolInvocation::getId, invocation.getId())
                .eq(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_RUNNING)
                .set(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_UNKNOWN)
                .set(AgentToolInvocation::getErrorMessage, limit(reason, 1000))
                .set(AgentToolInvocation::getCompletedAt, LocalDateTime.now()));
    }

    /** Marks an interrupted invocation as settled only when its owning tool proved its effect exists. */
    @Transactional
    public void reconcileInvocation(AgentToolInvocation invocation, JsonNode result) {
        if (!enabled || invocation == null || invocation.getId() == null) return;
        invocationMapper.update(null, new LambdaUpdateWrapper<AgentToolInvocation>()
                .eq(AgentToolInvocation::getId, invocation.getId())
                .eq(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_UNKNOWN)
                .set(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_SUCCEEDED)
                .set(AgentToolInvocation::getResultJson, result == null ? null : result.toString())
                .set(AgentToolInvocation::getErrorMessage, null)
                .set(AgentToolInvocation::getCompletedAt, LocalDateTime.now()));
    }

    /** Closes the run beside the existing conversation terminal transition. */
    @Transactional
    public void completeRun(Long conversationId, Long userId, long runVersion) {
        transitionRun(conversationId, userId, runVersion, AgentRun.STATUS_COMPLETED, null);
    }

    @Transactional
    public void failRun(Long conversationId, Long userId, long runVersion, String reason) {
        transitionRun(conversationId, userId, runVersion, AgentRun.STATUS_FAILED, reason);
    }

    private void transitionStep(AgentStep step, String status, String reason) {
        if (!enabled || step == null || step.getId() == null) return;
        stepMapper.update(null, new LambdaUpdateWrapper<AgentStep>()
                .eq(AgentStep::getId, step.getId())
                .eq(AgentStep::getStatus, AgentStep.STATUS_RUNNING)
                .set(AgentStep::getStatus, status)
                .set(AgentStep::getFailureReason, limit(reason, 1000))
                .set(AgentStep::getCompletedAt, LocalDateTime.now()));
    }

    private void transitionInvocation(AgentToolInvocation invocation, String status,
                                      String result, String error) {
        if (!enabled || invocation == null || invocation.getId() == null) return;
        invocationMapper.update(null, new LambdaUpdateWrapper<AgentToolInvocation>()
                .eq(AgentToolInvocation::getId, invocation.getId())
                .eq(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_RUNNING)
                .set(AgentToolInvocation::getStatus, status)
                .set(AgentToolInvocation::getResultJson, result)
                .set(AgentToolInvocation::getErrorMessage, limit(error, 1000))
                .set(AgentToolInvocation::getCompletedAt, LocalDateTime.now()));
    }

    private void transitionRun(Long conversationId, Long userId, long runVersion, String status,
                               String reason) {
        if (!enabled) return;
        AgentRun run = findRun(conversationId, runVersion);
        if (run == null) return;
        int changed = runMapper.update(null, new LambdaUpdateWrapper<AgentRun>()
                .eq(AgentRun::getId, run.getId())
                .eq(AgentRun::getUserId, userId)
                .eq(AgentRun::getStatus, AgentRun.STATUS_RUNNING)
                .set(AgentRun::getStatus, status)
                .set(AgentRun::getFailureReason, limit(reason, 1000))
                .set(AgentRun::getCompletedAt, LocalDateTime.now()));
        if (changed == 1 && AgentRun.STATUS_FAILED.equals(status)) {
            // A worker can disappear after a remote side effect starts. Keep the invocation visible
            // as unknown instead of allowing a later retry to treat it as never executed.
            stepMapper.update(null, new LambdaUpdateWrapper<AgentStep>()
                    .eq(AgentStep::getRunId, run.getId())
                    .eq(AgentStep::getStatus, AgentStep.STATUS_RUNNING)
                    .set(AgentStep::getStatus, AgentStep.STATUS_FAILED)
                    .set(AgentStep::getFailureReason, limit(reason, 1000))
                    .set(AgentStep::getCompletedAt, LocalDateTime.now()));
            invocationMapper.update(null, new LambdaUpdateWrapper<AgentToolInvocation>()
                    .eq(AgentToolInvocation::getRunId, run.getId())
                    .eq(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_RUNNING)
                    .set(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_UNKNOWN)
                    .set(AgentToolInvocation::getErrorMessage, limit(reason, 1000))
                    .set(AgentToolInvocation::getCompletedAt, LocalDateTime.now()));
        }
    }

    private AgentRun requireRunningRun(Long conversationId, Long userId, long runVersion) {
        requireActiveConversation(conversationId, userId, runVersion);
        AgentRun run = findRun(conversationId, runVersion);
        if (run == null || !AgentRun.STATUS_RUNNING.equals(run.getStatus())) {
            throw new AgentRunSupersededException(conversationId, runVersion);
        }
        return run;
    }

    private void requireActiveConversation(Long conversationId, Long userId, long runVersion) {
        var conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !userId.equals(conversation.getUserId())
                || !Long.valueOf(runVersion).equals(conversation.getRunVersion())
                || !"running".equals(conversation.getStatus())) {
            throw new AgentRunSupersededException(conversationId, runVersion);
        }
    }

    private AgentRun findRun(Long conversationId, long runVersion) {
        return runMapper.selectOne(new LambdaQueryWrapper<AgentRun>()
                .eq(AgentRun::getConversationId, conversationId)
                .eq(AgentRun::getRunVersion, runVersion));
    }

    private AgentStep disabledStep(int stepNo) {
        AgentStep step = new AgentStep();
        step.setId(-1L);
        step.setStepNo(stepNo);
        return step;
    }

    private AgentToolInvocation disabledInvocation(String invocationId, String toolName) {
        AgentToolInvocation invocation = new AgentToolInvocation();
        invocation.setId(-1L);
        invocation.setInvocationId(invocationId);
        invocation.setToolName(toolName);
        return invocation;
    }

    private String limit(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }
}
