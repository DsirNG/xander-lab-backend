package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.agent.domain.AgentApproval;
import com.xander.lab.agent.domain.AgentRun;
import com.xander.lab.agent.domain.AgentStep;
import com.xander.lab.agent.domain.AgentToolInvocation;
import com.xander.lab.agent.infrastructure.AgentApprovalMapper;
import com.xander.lab.agent.infrastructure.AgentRunMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

/** Creates immutable approval requests for high-impact agent tool calls. */
@Service
public class AgentApprovalService {
    /**
     * 参数在审批期间变了时的说明。
     *
     * <p>它会被写进 {@code decision_reason} 并作为事件载荷发给前端，所以要能让用户看懂
     * "为什么我点了批准却说没执行"。</p>
     */
    private static final String DRIFTED_REASON = "该动作的参数在审批期间已变更，无法按原样执行，请重新发起";

    private final AgentApprovalMapper approvalMapper;
    private final AgentRunMapper runMapper;
    private final AgentExecutionJournal executionJournal;
    private final AgentRunLifecycle runLifecycle;
    private final ObjectMapper objectMapper;
    private final TaskExecutor agentTaskExecutor;
    private final ObjectProvider<AgentConversationService> conversationServiceProvider;

    public AgentApprovalService(AgentApprovalMapper approvalMapper, AgentRunMapper runMapper,
                                AgentExecutionJournal executionJournal,
                                AgentRunLifecycle runLifecycle,
                                ObjectMapper objectMapper,
                                @Qualifier("agentTaskExecutor") TaskExecutor agentTaskExecutor,
                                ObjectProvider<AgentConversationService> conversationServiceProvider) {
        this.approvalMapper = approvalMapper;
        this.runMapper = runMapper;
        this.executionJournal = executionJournal;
        this.runLifecycle = runLifecycle;
        this.objectMapper = objectMapper;
        this.agentTaskExecutor = agentTaskExecutor;
        this.conversationServiceProvider = conversationServiceProvider;
    }

    /** Persists the exact user-visible action before any requested tool side effect can start. */
    @Transactional
    public AgentApproval request(AgentStep step, AgentToolInvocation invocation, Long userId,
                                 Long conversationId, String reason, JsonNode arguments) {
        AgentApproval approval = new AgentApproval();
        approval.setUserId(userId);
        approval.setConversationId(conversationId);
        approval.setRunId(step.getRunId());
        approval.setInvocationDbId(invocation.getId());
        approval.setInvocationId(invocation.getInvocationId());
        approval.setToolName(invocation.getToolName());
        // 与 Effect 的漂移检测共用同一份归一化实现。各写一份的话，两处规则迟早分叉，
        // 而两个指纹之间没有任何交叉校验能发现这件事。
        approval.setRequestFingerprint(AgentArgsFingerprint.of(invocation.getToolName(), arguments));
        approval.setSummaryJson(summary(invocation.getToolName(), reason, arguments));
        approval.setStatus(AgentApproval.STATUS_PENDING);
        if (approvalMapper.insert(approval) != 1) {
            throw new IllegalStateException("工具审批请求创建失败");
        }
        return approval;
    }

    /** Lists a conversation owner's approval history, including pending requests after a reload. */
    @Transactional(readOnly = true)
    public java.util.List<AgentApproval> list(Long conversationId, Long userId) {
        return approvalMapper.selectList(new QueryWrapper<AgentApproval>()
                .eq("conversation_id", conversationId)
                .eq("user_id", userId)
                .orderByDesc("id"));
    }

    /**
     * Records an owner decision and schedules the original durable invocation after the state change commits.
     *
     * <p>批准之前会先校验"用户批准的动作"与"当前这条调用"还是不是同一件事（见
     * {@link #driftedFromApprovedRequest}）。校验不过时走**拒绝**这条路，而不是抛异常——
     * 抛异常会把这一轮永久留在 {@code awaiting_approval} 里：{@code claim()} 拒绝待审批的会话，
     * 而 {@code resume()} 需要一次决定，于是用户被锁在自己的会话外面。那是 B2 修掉的那一类故障，
     * 不能从这条新路径上重新长出来。</p>
     */
    @Transactional
    public AgentApproval decide(Long conversationId, Long approvalId, Long userId, boolean approved,
                                String decisionReason) {
        AgentApproval approval = requirePending(conversationId, approvalId, userId);
        AgentRun run = requireRun(approval, userId);
        AgentToolInvocation invocation = executionJournal.findInvocation(approval.getInvocationDbId())
                .orElse(null);
        if (approved && driftedFromApprovedRequest(approval, invocation)) {
            return recordDecision(conversationId, approval, userId, run, false, DRIFTED_REASON, invocation);
        }
        return recordDecision(conversationId, approval, userId, run, approved, decisionReason, invocation);
    }

    private AgentApproval recordDecision(Long conversationId, AgentApproval approval, Long userId,
                                         AgentRun run, boolean approved, String decisionReason,
                                         AgentToolInvocation invocation) {
        String targetStatus = approved ? AgentApproval.STATUS_APPROVED : AgentApproval.STATUS_REJECTED;
        int decided = approvalMapper.update(null, new UpdateWrapper<AgentApproval>()
                .eq("id", approval.getId())
                .eq("user_id", userId)
                .eq("conversation_id", conversationId)
                .eq("status", AgentApproval.STATUS_PENDING)
                .set("status", targetStatus)
                .set("decision_reason", limit(decisionReason, 500))
                .set("decided_at", LocalDateTime.now()));
        if (decided != 1) throw new IllegalStateException("审批状态已变化");
        approval.setStatus(targetStatus);
        approval.setDecisionReason(limit(decisionReason, 500));
        approval.setDecidedAt(LocalDateTime.now());

        if (!approved) {
            executionJournal.rejectWaitingInvocation(approval.getInvocationDbId(), "用户拒绝审批");
            // 被拒绝 = 这个业务动作被明确放弃。留着 OPEN 的话，模型下一次重发同样的调用会接上它，
            // 那等于绕过刚刚被拒绝的这次审批。
            if (invocation != null) executionJournal.abandonEffect(invocation.getEffectId());
            AgentEventResult event = new AgentEventResult("approval_rejected", java.util.Map.of(
                    "approvalId", approval.getId(), "invocationId", approval.getInvocationId(),
                    "tool", approval.getToolName()));
            runLifecycle.completeAfterApproval(conversationId, userId, run.getRunVersion(), run.getId(),
                    event.type(), event.payload());
            return approval;
        }

        AgentToolInvocation started = executionJournal.startApprovedInvocation(approval.getInvocationDbId());
        runLifecycle.resumeAfterApproval(conversationId, userId, run.getRunVersion(), run.getId(),
                java.util.Map.of("approvalId", approval.getId(), "invocationId", approval.getInvocationId(),
                        "tool", approval.getToolName()));
        scheduleAfterCommit(
                () -> conversationServiceProvider.getObject().resumeApprovedInvocation(approval, started),
                () -> conversationServiceProvider.getObject().failResumedApproval(
                        approval, started, "审批恢复工作线程繁忙，请稍后重试"));
        return approval;
    }

    /**
     * 用户批准的那个动作，和现在这条调用，还是不是同一件事。
     *
     * <p><b>今天这个校验不可能失败。</b>参数在 {@code startInvocation} 时落库且之后不可变，
     * 而 {@code uk_agent_approval_invocation} 把审批唯一绑定到那一条 invocation，
     * 所以"执行的就是被批准的那个参数"由另外两个机制保证着。</p>
     *
     * <p>那为什么还要加它？因为 {@code agent_approval.request_fingerprint} 的列注释承诺了
     * "变更操作必须重新审批"，而在此之前**这个列被计算、落库、然后从未被读取**——一个承诺了却
     * 不存在的机制比没有注释更危险：以后有人加"重新审批"或"重试这条审批"的路径时，
     * 他会以为校验已经在了。所以这里把它接上，让注释从幽灵变成真机制。</p>
     *
     * <p>正因为它今天不可能失败，任何一次失败都必须是**假警报**，不能由它作废用户的审批——
     * 所以 {@link AgentArgsFingerprint#matches} 兼容了迁移前落库的旧算法。</p>
     *
     * @return 读不到记录时返回 {@code false}：那不是"参数变了"，而是记录没了，
     *         由后续的 {@code startApprovedInvocation} 报出来
     */
    private boolean driftedFromApprovedRequest(AgentApproval approval, AgentToolInvocation invocation) {
        if (invocation == null) return false;
        return !AgentArgsFingerprint.matches(approval.getRequestFingerprint(),
                invocation.getToolName(), readArguments(invocation));
    }

    /**
     * 读回落库的工具参数。
     *
     * <p>解析失败时抛异常而不是当作"参数变了"：落库的 JSON 是由已校验的 {@code JsonNode}
     * 序列化来的，解析不了说明这一行本身有问题，而误判成漂移会白白作废用户的一次审批。</p>
     */
    private JsonNode readArguments(AgentToolInvocation invocation) {
        String json = invocation.getArgumentsJson();
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("审批关联的工具参数无法解析", exception);
        }
    }

    private void scheduleAfterCommit(Runnable continuation, Runnable onRejected) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            submit(continuation, onRejected);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit(continuation, onRejected);
            }
        });
    }

    private void submit(Runnable continuation, Runnable onRejected) {
        try {
            agentTaskExecutor.execute(continuation);
        } catch (RuntimeException rejected) {
            onRejected.run();
        }
    }

    private AgentApproval requirePending(Long conversationId, Long approvalId, Long userId) {
        AgentApproval approval = approvalMapper.selectOne(new QueryWrapper<AgentApproval>()
                .eq("id", approvalId)
                .eq("user_id", userId)
                .eq("conversation_id", conversationId));
        if (approval == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "审批请求不存在");
        if (!AgentApproval.STATUS_PENDING.equals(approval.getStatus())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "审批请求已处理");
        }
        return approval;
    }

    private AgentRun requireRun(AgentApproval approval, Long userId) {
        AgentRun run = runMapper.selectById(approval.getRunId());
        if (run == null || !userId.equals(run.getUserId())
                || !AgentRun.STATUS_WAITING_APPROVAL.equals(run.getStatus())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "审批关联的执行已变化");
        }
        return run;
    }

    private String limit(String value, int max) {
        if (value == null || value.isBlank()) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record AgentEventResult(String type, Object payload) {
    }

    private String summary(String toolName, String reason, JsonNode arguments) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("tool", toolName);
        summary.put("reason", reason);
        summary.set("args", arguments == null ? objectMapper.createObjectNode() : arguments);
        return summary.toString();
    }
}
