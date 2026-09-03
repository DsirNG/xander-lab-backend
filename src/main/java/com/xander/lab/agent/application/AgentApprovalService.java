package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.agent.domain.AgentApproval;
import com.xander.lab.agent.domain.AgentRun;
import com.xander.lab.agent.domain.AgentStep;
import com.xander.lab.agent.domain.AgentToolInvocation;
import com.xander.lab.agent.infrastructure.AgentApprovalMapper;
import com.xander.lab.agent.infrastructure.AgentRunMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Creates immutable approval requests for high-impact agent tool calls. */
@Service
public class AgentApprovalService {
    private final AgentApprovalMapper approvalMapper;
    private final AgentRunMapper runMapper;
    private final AgentToolRegistry toolRegistry;
    private final AgentExecutionJournal executionJournal;
    private final AgentRunLifecycle runLifecycle;
    private final AgentEventService eventService;
    private final ObjectMapper objectMapper;

    public AgentApprovalService(AgentApprovalMapper approvalMapper, AgentRunMapper runMapper,
                                AgentToolRegistry toolRegistry, AgentExecutionJournal executionJournal,
                                AgentRunLifecycle runLifecycle, AgentEventService eventService,
                                ObjectMapper objectMapper) {
        this.approvalMapper = approvalMapper;
        this.runMapper = runMapper;
        this.toolRegistry = toolRegistry;
        this.executionJournal = executionJournal;
        this.runLifecycle = runLifecycle;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
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
        approval.setRequestFingerprint(fingerprint(invocation.getToolName(), arguments));
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
     * Records an owner decision and, on approval, executes the original durable invocation exactly once.
     * The side effect deliberately runs outside a database transaction.
     */
    public AgentApproval decide(Long conversationId, Long approvalId, Long userId, boolean approved,
                                String decisionReason) {
        AgentApproval approval = requirePending(conversationId, approvalId, userId);
        String targetStatus = approved ? AgentApproval.STATUS_APPROVED : AgentApproval.STATUS_REJECTED;
        int decided = approvalMapper.update(null, new UpdateWrapper<AgentApproval>()
                .eq("id", approvalId)
                .eq("user_id", userId)
                .eq("conversation_id", conversationId)
                .eq("status", AgentApproval.STATUS_PENDING)
                .set("status", targetStatus)
                .set("decision_reason", limit(decisionReason, 500))
                .set("decided_at", java.time.LocalDateTime.now()));
        if (decided != 1) throw new IllegalStateException("审批状态已变化");
        approval.setStatus(targetStatus);
        approval.setDecisionReason(limit(decisionReason, 500));
        approval.setDecidedAt(java.time.LocalDateTime.now());

        AgentRun run = requireRun(approval, userId);
        if (!approved) {
            executionJournal.rejectWaitingInvocation(approval.getInvocationDbId(), "用户拒绝审批");
            AgentEventResult event = new AgentEventResult("approval_rejected", java.util.Map.of(
                    "approvalId", approvalId, "invocationId", approval.getInvocationId(),
                    "tool", approval.getToolName()));
            runLifecycle.completeAfterApproval(conversationId, userId, run.getRunVersion(), run.getId(),
                    event.type(), event.payload());
            return approval;
        }

        AgentToolInvocation invocation = executionJournal.startApprovedInvocation(approval.getInvocationDbId());
        AgentTool tool = toolRegistry.get(invocation.getToolName());
        if (tool == null) {
            return failApprovedExecution(approval, run, invocation, "审批关联的工具不存在");
        }
        try {
            JsonNode arguments = objectMapper.readTree(invocation.getArgumentsJson());
            eventService.storeForRun(conversationId, userId, run.getRunVersion(), "approval_approved",
                    java.util.Map.of("approvalId", approvalId, "invocationId", approval.getInvocationId(),
                            "tool", approval.getToolName()), false);
            JsonNode result = tool.execute(new AgentToolContext(userId, conversationId, run.getRunVersion(),
                    invocation.getInvocationId()), arguments, new AgentTool.ProgressListener() {
                @Override public void onProgress(String message) { }
            });
            executionJournal.completeInvocation(invocation, result);
            eventService.storeForRun(conversationId, userId, run.getRunVersion(), "tool_end",
                    java.util.Map.of("tool", invocation.getToolName(), "result", result), false);
            runLifecycle.completeAfterApproval(conversationId, userId, run.getRunVersion(), run.getId(),
                    "approval_executed", java.util.Map.of("approvalId", approvalId,
                            "invocationId", invocation.getInvocationId(), "tool", invocation.getToolName()));
            return approval;
        } catch (Exception exception) {
            return failApprovedExecution(approval, run, invocation, safeMessage(exception));
        }
    }

    private AgentApproval failApprovedExecution(AgentApproval approval, AgentRun run,
                                                AgentToolInvocation invocation, String reason) {
        executionJournal.failInvocation(invocation, reason);
        runLifecycle.failAfterApproval(approval.getConversationId(), approval.getUserId(), run.getRunVersion(),
                run.getId(), reason, java.util.Map.of("tool", approval.getToolName(), "error", reason));
        return approval;
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

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "审批后的工具执行失败";
        return limit(message, 1000);
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

    private String fingerprint(String toolName, JsonNode arguments) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((toolName + "\n" + (arguments == null ? "{}" : arguments.toString()))
                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder(digest.length * 2);
            for (byte value : digest) text.append(String.format("%02x", value));
            return text.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
