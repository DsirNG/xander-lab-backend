package com.xander.lab.agent.application;

import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentEvent;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

/** Coordinates one already-claimed agent turn around the durable run lifecycle. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTurnCoordinator {
    private final AgentConversationMapper conversationMapper;
    private final AgentEventService eventService;
    private final AgentRunLifecycle runLifecycle;

    /** Executes work outside transactions while preserving the claimed generation's terminal semantics. */
    AgentConversation execute(Long conversationId, Long userId, long runVersion,
                              Consumer<AgentEvent> onEvent, ClaimedTurnWork work) {
        ScheduledFuture<?> leaseRenewal;
        try {
            leaseRenewal = runLifecycle.startLeaseRenewal(conversationId, userId, runVersion);
        } catch (RuntimeException exception) {
            failBeforeStart(conversationId, userId, runVersion, "智能体执行租约服务繁忙，请稍后重试", onEvent);
            throw exception;
        }
        try {
            return work.run();
        } catch (AgentRunSupersededException exception) {
            log.info("agent worker generation superseded for conversation {} and run {}", conversationId, runVersion);
            return requireOwned(conversationId, userId);
        } catch (RuntimeException exception) {
            log.error("agent turn execution failed conversationId={} userId={} runVersion={} phase=turn exceptionType={} message={}",
                    conversationId, userId, runVersion, exception.getClass().getName(), exception.getMessage(), exception);
            AgentEvent errorEvent = null;
            try {
                errorEvent = runLifecycle.fail(conversationId, userId, runVersion, safeMessage(exception));
            } catch (AgentRunSupersededException superseded) {
                log.info("agent failure ignored for superseded conversation {} and run {}", conversationId, runVersion);
                return requireOwned(conversationId, userId);
            } catch (RuntimeException persistenceFailure) {
                exception.addSuppressed(persistenceFailure);
                log.error("agent terminal failure could not be persisted conversationId={} userId={} runVersion={} exceptionType={} message={}",
                        conversationId, userId, runVersion, persistenceFailure.getClass().getName(),
                        persistenceFailure.getMessage(), persistenceFailure);
            }
            if (errorEvent != null) eventService.deliver(errorEvent, onEvent);
            throw exception;
        } finally {
            leaseRenewal.cancel(false);
        }
    }

    /** Persists a terminal error when a claimed turn cannot enter the worker pool. */
    void failBeforeStart(Long conversationId, Long userId, long runVersion, String message,
                         Consumer<AgentEvent> onEvent) {
        try {
            AgentEvent errorEvent = runLifecycle.fail(conversationId, userId, runVersion, message);
            log.info("agent claimed turn failed before worker execution for conversation {} and event {}",
                    conversationId, errorEvent.getId());
            if (onEvent != null) eventService.deliver(errorEvent, onEvent);
        } catch (AgentRunSupersededException ignored) {
            log.info("agent queue rejection ignored for superseded conversation {} and run {}",
                    conversationId, runVersion);
        }
    }

    private AgentConversation requireOwned(Long conversationId, Long userId) {
        AgentConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || userId == null || !userId.equals(conversation.getUserId())) {
            throw new AgentRunSupersededException(conversationId, 0L);
        }
        return conversation;
    }

    private String safeMessage(RuntimeException exception) {
        if (exception instanceof ResponseStatusException response && response.getReason() != null) {
            return response.getReason();
        }
        if (exception instanceof IllegalArgumentException || exception instanceof CancellationException) {
            return StringUtils.hasText(exception.getMessage()) ? exception.getMessage() : "请求未完成";
        }
        if (exception instanceof IllegalStateException && StringUtils.hasText(exception.getMessage())
                && !exception.getMessage().contains("http") && !exception.getMessage().contains("HTTP")) {
            return exception.getMessage();
        }
        return "智能体任务失败，请稍后重试";
    }
}

@FunctionalInterface
interface ClaimedTurnWork {
    AgentConversation run();
}
