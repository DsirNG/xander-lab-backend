package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentEvent;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import com.xander.lab.agent.infrastructure.AgentConversationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/** Owns fenced durable terminal transitions for an agent conversation run. */
@Service
@RequiredArgsConstructor
public class AgentRunLifecycle {
    private final AgentConversationMapper conversationMapper;
    private final AgentEventService eventService;
    private final AgentExecutionJournal executionJournal;
    private final AgentConversationProperties properties;
    private final TransactionTemplate transactionTemplate;

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
