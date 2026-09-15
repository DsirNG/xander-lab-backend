package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentEvent;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import com.xander.lab.agent.infrastructure.AgentConversationProperties;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Owns fenced durable terminal transitions for an agent conversation run. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRunLifecycle {
    private static final long MAX_LEASE_RENEW_INTERVAL_SECONDS = 60L;

    private final AgentConversationMapper conversationMapper;
    private final AgentEventService eventService;
    private final AgentExecutionJournal executionJournal;
    private final AgentConversationProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final ScheduledExecutorService agentLeaseRenewer;

    /** Claims an idle or failed conversation for a new fenced execution generation. */
    long claim(Long conversationId, Long userId) {
        int updated = conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                .eq(AgentConversation::getId, conversationId)
                .eq(AgentConversation::getUserId, userId)
                .and(wrapper -> wrapper.eq(AgentConversation::getStatus, "ready")
                        .or()
                        .eq(AgentConversation::getStatus, "failed"))
                .set(AgentConversation::getStatus, "running")
                .setSql("run_version = run_version + 1")
                .set(AgentConversation::getErrorMessage, null)
                .set(AgentConversation::getCancelRequested, 0));
        if (updated == 1) {
            AgentConversation claimed = requireOwned(conversationId, userId);
            if (claimed.getRunVersion() == null) {
                throw new IllegalStateException("智能体执行代次缺失");
            }
            return claimed.getRunVersion();
        }
        AgentConversation conversation = requireOwned(conversationId, userId);
        if ("running".equals(conversation.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "智能体正在执行中，请稍候");
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "当前会话状态不能继续对话");
    }

    /** Records a cancellation request only for the currently fenced running generation. */
    AgentConversation requestCancellation(Long conversationId, Long userId) {
        AgentConversation current = requireOwned(conversationId, userId);
        long runVersion = current.getRunVersion() == null ? 0L : current.getRunVersion();
        transactionTemplate.executeWithoutResult(status -> conversationMapper.update(
                null, new LambdaUpdateWrapper<AgentConversation>()
                        .eq(AgentConversation::getId, conversationId)
                        .eq(AgentConversation::getUserId, userId)
                        .eq(AgentConversation::getStatus, "running")
                        .eq(AgentConversation::getRunVersion, runVersion)
                        .set(AgentConversation::getCancelRequested, 1)));
        return requireOwned(conversationId, userId);
    }

    /** Returns the cancellation flag after verifying this worker still owns the running generation. */
    boolean isCancellationRequested(Long conversationId, Long userId, long runVersion) {
        AgentConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !userId.equals(conversation.getUserId())
                || !Long.valueOf(runVersion).equals(conversation.getRunVersion())
                || !"running".equals(conversation.getStatus())) {
            throw new AgentRunSupersededException(conversationId, runVersion);
        }
        return Integer.valueOf(1).equals(conversation.getCancelRequested());
    }

    /** Keeps a claimed worker recoverable while model or tool calls produce no durable progress. */
    ScheduledFuture<?> startLeaseRenewal(Long conversationId, Long userId, long runVersion) {
        long staleSeconds = Math.max(60L, Math.max(1, properties.getStaleConversationMinutes()) * 60L);
        long intervalSeconds = Math.max(1L, Math.min(MAX_LEASE_RENEW_INTERVAL_SECONDS, staleSeconds / 3L));
        return agentLeaseRenewer.scheduleAtFixedRate(() -> {
            try {
                int renewed = conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                        .eq(AgentConversation::getId, conversationId)
                        .eq(AgentConversation::getUserId, userId)
                        .eq(AgentConversation::getStatus, "running")
                        .eq(AgentConversation::getRunVersion, runVersion)
                        .setSql("updated_at = CURRENT_TIMESTAMP"));
                if (renewed != 1) {
                    throw new AgentRunSupersededException(conversationId, runVersion);
                }
            } catch (AgentRunSupersededException superseded) {
                log.debug("agent lease renewal stopped for superseded conversation {} and run {}",
                        conversationId, runVersion);
                throw superseded;
            } catch (RuntimeException exception) {
                log.warn("agent lease renewal failed for conversation {} and run {}: {}",
                        conversationId, runVersion, exception.getClass().getSimpleName());
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    /** Moves a running generation to ready and records its durable complete event. */
    TerminalTransition complete(Long conversationId, Long userId, long runVersion) {
        return transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            AgentConversation current = requireOwned(conversationId, userId);
            int updated = conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                    .eq(AgentConversation::getId, conversationId)
                    .eq(AgentConversation::getUserId, userId)
                    .eq(AgentConversation::getStatus, "running")
                    .eq(AgentConversation::getRunVersion, runVersion)
                    .set(AgentConversation::getStatus, "ready")
                    .set(AgentConversation::getErrorMessage, null)
                    .set(AgentConversation::getCancelRequested, 0)
                    .set(AgentConversation::getLastCompletedAt, now));
            if (updated != 1) throw new AgentRunSupersededException(conversationId, runVersion);
            executionJournal.completeRun(conversationId, userId, runVersion);
            current.setStatus("ready");
            current.setErrorMessage(null);
            current.setCancelRequested(0);
            current.setLastCompletedAt(now);
            AgentEvent event = eventService.storeForRun(
                    conversationId, userId, runVersion, "complete", current, false);
            return new TerminalTransition(current, event);
        });
    }

    /** Moves a running generation to failed and records its durable error event. */
    AgentEvent fail(Long conversationId, Long userId, long runVersion, String errorMessage) {
        return transactionTemplate.execute(status -> {
            String safeError = limit(errorMessage == null || errorMessage.isBlank()
                    ? "智能体任务失败，请稍后重试" : errorMessage, 1000);
            LocalDateTime now = LocalDateTime.now();
            int updated = conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                    .eq(AgentConversation::getId, conversationId)
                    .eq(AgentConversation::getUserId, userId)
                    .eq(AgentConversation::getStatus, "running")
                    .eq(AgentConversation::getRunVersion, runVersion)
                    .set(AgentConversation::getStatus, "failed")
                    .set(AgentConversation::getErrorMessage, safeError)
                    .set(AgentConversation::getLastCompletedAt, now));
            if (updated != 1) throw new AgentRunSupersededException(conversationId, runVersion);
            executionJournal.failRun(conversationId, userId, runVersion, safeError);
            return eventService.storeForRun(conversationId, userId, runVersion, "error", safeError, false);
        });
    }

    /** Stops the worker lease while retaining the run and invocation for an explicit user decision. */
    AgentEvent waitForApproval(Long conversationId, Long userId, long runVersion,
                                com.xander.lab.agent.domain.AgentApproval approval) {
        return transactionTemplate.execute(status -> {
            int updated = conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                    .eq(AgentConversation::getId, conversationId)
                    .eq(AgentConversation::getUserId, userId)
                    .eq(AgentConversation::getStatus, "running")
                    .eq(AgentConversation::getRunVersion, runVersion)
                    .set(AgentConversation::getStatus, "awaiting_approval")
                    .set(AgentConversation::getCancelRequested, 0));
            if (updated != 1) throw new AgentRunSupersededException(conversationId, runVersion);
            executionJournal.waitRunForApproval(conversationId, userId, runVersion);
            java.util.Map<String, Object> payload = java.util.Map.of(
                    "approvalId", approval.getId(),
                    "invocationId", approval.getInvocationId(),
                    "tool", approval.getToolName(),
                    "summary", approval.getSummaryJson());
            return eventService.storeForRun(conversationId, userId, runVersion,
                    "approval_required", payload, false);
        });
    }

    /** Closes an approval-paused run after a decision has either executed or rejected its one call. */
    AgentEvent completeAfterApproval(Long conversationId, Long userId, long runVersion, Long runId,
                                     String eventType, Object payload) {
        return transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            int updated = conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                    .eq(AgentConversation::getId, conversationId)
                    .eq(AgentConversation::getUserId, userId)
                    .eq(AgentConversation::getStatus, "awaiting_approval")
                    .eq(AgentConversation::getRunVersion, runVersion)
                    .set(AgentConversation::getStatus, "ready")
                    .set(AgentConversation::getErrorMessage, null)
                    .set(AgentConversation::getLastCompletedAt, now));
            if (updated != 1) throw new AgentRunSupersededException(conversationId, runVersion);
            executionJournal.completeWaitingRun(runId, userId);
            eventService.storeForRun(conversationId, userId, runVersion, eventType, payload, false);
            return eventService.storeForRun(conversationId, userId, runVersion, "complete",
                    java.util.Map.of("status", "ready"), false);
        });
    }

    /** Reopens the paused generation before its approved invocation and remaining model steps continue. */
    AgentEvent resumeAfterApproval(Long conversationId, Long userId, long runVersion, Long runId,
                                   Object payload) {
        return transactionTemplate.execute(status -> {
            int updated = conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                    .eq(AgentConversation::getId, conversationId)
                    .eq(AgentConversation::getUserId, userId)
                    .eq(AgentConversation::getStatus, "awaiting_approval")
                    .eq(AgentConversation::getRunVersion, runVersion)
                    .set(AgentConversation::getStatus, "running")
                    .set(AgentConversation::getCancelRequested, 0));
            if (updated != 1) throw new AgentRunSupersededException(conversationId, runVersion);
            executionJournal.resumeWaitingRun(runId, userId);
            return eventService.storeActive(conversationId, userId, runVersion, "approval_approved", payload);
        });
    }

    /** Recovers an expired running generation without overwriting a newer owner or generation. */
    void recoverStale(AgentConversation conversation, Long userId) {
        if (!"running".equals(conversation.getStatus()) || conversation.getUpdatedAt() == null) return;
        int staleMinutes = Math.max(1, properties.getStaleConversationMinutes());
        if (conversation.getUpdatedAt().isAfter(LocalDateTime.now().minusMinutes(staleMinutes))) return;
        transactionTemplate.executeWithoutResult(status -> {
            String interrupted = "上一轮执行中断，可继续对话";
            LocalDateTime now = LocalDateTime.now();
            int changed = conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                    .eq(AgentConversation::getId, conversation.getId())
                    .eq(AgentConversation::getUserId, userId)
                    .eq(AgentConversation::getStatus, "running")
                    .eq(AgentConversation::getRunVersion, conversation.getRunVersion())
                    .le(AgentConversation::getUpdatedAt, LocalDateTime.now().minusMinutes(staleMinutes))
                    .set(AgentConversation::getStatus, "failed")
                    .set(AgentConversation::getErrorMessage, interrupted)
                    .set(AgentConversation::getLastCompletedAt, now));
            long runVersion = conversation.getRunVersion() == null ? 0L : conversation.getRunVersion();
            if (changed != 1) return;
            executionJournal.failRun(conversation.getId(), userId, runVersion, interrupted);
            if (eventService.latestTerminal(conversation.getId(), runVersion) == null) {
                eventService.storeForRun(conversation.getId(), userId, runVersion, "error", interrupted, false);
            }
        });
    }

    private AgentConversation requireOwned(Long conversationId, Long userId) {
        AgentConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !userId.equals(conversation.getUserId())) {
            throw new AgentRunSupersededException(conversationId, 0L);
        }
        return conversation;
    }

    private String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}

/** Durable terminal state and event returned to the facade for billing and live fan-out. */
record TerminalTransition(AgentConversation conversation, AgentEvent event) {
}
