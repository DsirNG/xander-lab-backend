package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.agent.domain.AgentRun;
import com.xander.lab.agent.domain.AgentToolInvocation;
import com.xander.lab.agent.infrastructure.AgentRunMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.time.LocalDateTime;

/**
 * Settles interrupted local tool calls by querying their owning business service.
 *
 * <p>This component never replays an unknown mutation. A tool must return a durable result for
 * the original invocation id before the journal can move from {@code UNKNOWN} to succeeded.</p>
 */
@Service
public class AgentToolInvocationReconciler {
    private static final Logger log = LoggerFactory.getLogger(AgentToolInvocationReconciler.class);
    private static final int BATCH_SIZE = 50;
    private static final int APPROVAL_EXECUTION_TIMEOUT_MINUTES = 10;

    private final AgentExecutionJournal executionJournal;
    private final AgentToolRegistry toolRegistry;
    private final AgentRunMapper runMapper;
    private final ObjectMapper objectMapper;

    public AgentToolInvocationReconciler(AgentExecutionJournal executionJournal,
                                         AgentToolRegistry toolRegistry,
                                         AgentRunMapper runMapper,
                                         ObjectMapper objectMapper) {
        this.executionJournal = executionJournal;
        this.toolRegistry = toolRegistry;
        this.runMapper = runMapper;
        this.objectMapper = objectMapper;
    }

    /** Periodically settles only effects that can be proven through an owning service lookup. */
    @Scheduled(fixedDelayString = "${agent-harness.reconciliation-delay-ms:60000}")
    public void reconcileUnknownInvocations() {
        recoverExpiredApprovedExecutions();
        for (AgentToolInvocation invocation : executionJournal.unknownInvocations(BATCH_SIZE)) {
            reconcile(invocation);
        }
    }

    private void recoverExpiredApprovedExecutions() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(APPROVAL_EXECUTION_TIMEOUT_MINUTES);
        for (AgentToolInvocation invocation : executionJournal.runningInvocationsBefore(cutoff, BATCH_SIZE)) {
            AgentRun run = runMapper.selectById(invocation.getRunId());
            if (run != null && AgentRun.STATUS_WAITING_APPROVAL.equals(run.getStatus())) {
                // The approval decision is durable, but the worker is gone. Never replay blindly.
                executionJournal.markInvocationUnknown(invocation, "审批后的工具执行超时，等待对账");
            }
        }
    }

    private void reconcile(AgentToolInvocation invocation) {
        AgentTool tool = toolRegistry.get(invocation.getToolName());
        AgentRun run = runMapper.selectById(invocation.getRunId());
        if (tool == null || run == null) {
            return;
        }
        try {
            JsonNode arguments = objectMapper.readTree(invocation.getArgumentsJson());
            Optional<JsonNode> result = tool.reconcile(new AgentToolContext(
                    run.getUserId(), run.getConversationId(), run.getRunVersion(), invocation.getInvocationId()),
                    arguments);
            result.ifPresent(value -> executionJournal.reconcileInvocation(invocation, value));
        } catch (Exception exception) {
            // Reconciliation is best effort and read-only; retain UNKNOWN for operator visibility.
            log.warn("Agent tool invocation reconciliation deferred: invocationId={}, tool={}",
                    invocation.getInvocationId(), invocation.getToolName());
        }
    }
}
