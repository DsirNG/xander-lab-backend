package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.agent.application.AgentTool.ProgressListener;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentEvent;
import com.xander.lab.agent.domain.AgentMemory;
import com.xander.lab.agent.domain.AgentMessage;
import com.xander.lab.agent.domain.AgentPlanItem;
import com.xander.lab.agent.domain.AgentAttachment;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import com.xander.lab.agent.infrastructure.AgentConversationProperties;
import com.xander.lab.agent.infrastructure.AgentMessageMapper;
import com.xander.lab.agent.infrastructure.AgentModelClient;
import com.xander.lab.billing.application.BillingService;
import com.xander.lab.billing.domain.BillingBiz;
import com.xander.lab.common.ModelCallResult;
import com.xander.lab.common.ModelUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 编排一轮智能体对话：模型自己决定下一步（继续调工具还是收口回复），
 * 工具结果作为观察回灌，直到模型认为完成或步数预算耗尽。
 *
 * <p>这一层承载"像智能体而不像工作流"的几个关键点：完整工具表每轮都交给模型（不按关键词预筛）、
 * 任务计划与长期记忆作为会话状态注入每一步、收口前过一道确定性自检，
 * 自检不通过就把批评意见回灌让它自己纠偏。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentConversationService {
    private static final int TOOL_DELTA_FLUSH_CHARS = 256;
    private static final int ANSWER_DELTA_FLUSH_CHARS = 24;
    private static final long MAX_LEASE_RENEW_INTERVAL_SECONDS = 60L;
    private static final String DEFAULT_TITLE = "新对话";
    /** 历史工具结果的截断长度：早期观察只保留摘要，够模型记得"做过什么"。 */
    private static final int TOOL_RESULT_HISTORY_CHARS = 800;
    /** 最近一次工具结果的截断长度：下一步推理主要依赖它，留足正文才能据实作答。 */
    private static final int TOOL_RESULT_LATEST_CHARS = 4000;

    /**
     * 模型常把 Markdown 反引号转义成 \`；CommonMark 会把 \` 渲染成裸反引号。
     * 归一化为真正的反引号，保证行内代码与代码围栏正确渲染。
     */
    static String normalizeAnswer(String text) {
        if (!StringUtils.hasText(text) || text.indexOf("\\`") < 0) return text;
        return text.replace("\\`", "`");
    }

    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final AgentModelClient modelClient;
    private final AgentToolRegistry toolRegistry;
    private final AgentConversationProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final AgentEventService eventService;
    private final ScheduledExecutorService agentLeaseRenewer;
    private final BillingService billingService;
    private final ObjectMapper objectMapper;
    private final AgentPlanService planService;
    private final AgentMemoryService memoryService;
    private final AgentTurnEvaluator turnEvaluator;

    /**
     * 根据首条消息生成标题并创建会话壳（ready、无消息、不启动轮次）。
     * 首条消息随后由 /messages/stream 原子持久化并执行，避免创建接口抢先启动 worker。
     */
    @Transactional
    public AgentConversation create(Long userId, String message) {
        String initialMessage = defaultText(message, "").trim();
        if (!StringUtils.hasText(initialMessage)) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        AgentConversation conversation = new AgentConversation();
        conversation.setUserId(userId);
        conversation.setTitle(excerpt(initialMessage, 40));
        conversation.setStatus("ready");
        conversation.setRunVersion(1L);
        conversation.setCancelRequested(0);
        conversationMapper.insert(conversation);
        return conversation;
    }

    /** Atomically claims and records a subsequent turn before an async worker is scheduled. */
    public AgentConversation prepareTurn(Long conversationId, Long userId, String message) {
        return prepareTurn(conversationId, userId, message, List.of());
    }

    /** Atomically claims and records a turn together with its immutable attachment snapshot. */
    public AgentConversation prepareTurn(Long conversationId, Long userId, String message,
                                         List<AgentAttachment> attachments) {
        String userMessage = defaultText(message, "").trim();
        if (!StringUtils.hasText(userMessage)) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        billingService.ensureAvailable(userId);
        return transactionTemplate.execute(status -> {
            long runVersion = claimConversation(conversationId, userId);
            // 新一轮诉求开始：上一轮的计划已经收口或作废，留着只会污染这一轮的自检。
            planService.clear(conversationId, userId);
            saveMessage(conversationId, "user", "message", null, userMessage,
                    serializeAttachments(attachments));
            AgentConversation claimed = requireOwned(conversationId, userId);
            if (isDefaultTitle(claimed.getTitle())) {
                claimed.setTitle(excerpt(userMessage, 40));
                conversationMapper.updateById(claimed);
            }
            if (!Long.valueOf(runVersion).equals(claimed.getRunVersion())) {
                throw new AgentRunSupersededException(conversationId, runVersion);
            }
            return claimed;
        });
    }

    /** Runs one already-claimed turn; all model and tool network calls remain outside transactions. */
    public AgentConversation executeClaimedTurn(Long conversationId, Long userId, long runVersion,
                                                Consumer<AgentEvent> onEvent) {
        return executeClaimedTurn(conversationId, userId, runVersion, null, onEvent);
    }

    /** Runs a claimed turn with its current input available for low-cost tool selection. */
    public AgentConversation executeClaimedTurn(Long conversationId, Long userId, long runVersion,
                                                String currentUserMessage,
                                                Consumer<AgentEvent> onEvent) {
        ScheduledFuture<?> leaseRenewal;
        try {
            leaseRenewal = startLeaseRenewal(conversationId, userId, runVersion);
        } catch (RuntimeException exception) {
            failClaimedTurn(conversationId, userId, runVersion,
                    "智能体执行租约服务繁忙，请稍后重试", onEvent);
            throw exception;
        }
        try {
            return runLoop(conversationId, userId, runVersion, currentUserMessage, onEvent);
        } catch (AgentRunSupersededException exception) {
            log.info("agent worker generation superseded for conversation {} and run {}",
                    conversationId, runVersion);
            return requireOwned(conversationId, userId);
        } catch (RuntimeException exception) {
            AgentEvent errorEvent = null;
            try {
                errorEvent = markFailed(conversationId, userId, runVersion, safeMessage(exception));
            } catch (AgentRunSupersededException superseded) {
                log.info("agent failure ignored for superseded conversation {} and run {}",
                        conversationId, runVersion);
                return requireOwned(conversationId, userId);
            } catch (RuntimeException persistenceFailure) {
                exception.addSuppressed(persistenceFailure);
                log.error("agent terminal failure could not be persisted for conversation {}",
                        conversationId, persistenceFailure);
            }
            if (errorEvent != null) {
                eventService.deliver(errorEvent, onEvent);
            }
            throw exception;
        } finally {
            leaseRenewal.cancel(false);
        }
    }

    /** Persists a terminal error when a claimed turn cannot be submitted to the worker pool. */
    public void failClaimedTurn(Long conversationId, Long userId, long runVersion, String message) {
        failClaimedTurn(conversationId, userId, runVersion, message, null);
    }

    private void failClaimedTurn(Long conversationId, Long userId, long runVersion, String message,
                                 Consumer<AgentEvent> onEvent) {
        try {
            AgentEvent errorEvent = markFailed(conversationId, userId, runVersion, message);
            log.info("agent claimed turn failed before worker execution for conversation {} and event {}",
                    conversationId, errorEvent.getId());
            if (onEvent != null) {
                eventService.deliver(errorEvent, onEvent);
            }
        } catch (AgentRunSupersededException ignored) {
            log.info("agent queue rejection ignored for superseded conversation {} and run {}",
                    conversationId, runVersion);
        }
    }

    /** Convenience entry point used by non-HTTP callers: claim, persist input, then execute. */
    public AgentConversation executeTurn(Long conversationId, Long userId, String message,
        Consumer<AgentEvent> onEvent) {
        AgentConversation claimed = prepareTurn(conversationId, userId, message);
        return executeClaimedTurn(conversationId, userId, claimed.getRunVersion(), message, onEvent);
    }

    private AgentConversation runLoop(Long conversationId, Long userId, long runVersion,
                                      String currentUserMessage, Consumer<AgentEvent> listener) {
        long turnStartedNanos = System.nanoTime();
        String latestUserMessage = StringUtils.hasText(currentUserMessage)
                ? currentUserMessage : latestUserMessage(conversationId, userId);
        // 工具表不再按关键词预筛：用不用、用哪个由模型自己判断。
        // 旧的关键词门禁会让匹配不上词表的诉求连工具都看不见，只能空口答应，那才是"工作流"。
        Set<String> allowedTools = toolRegistry.allowedNames();
        String toolDescriptions = toolRegistry.describeTools(allowedTools);
        String memoryContext = recallMemoryContext(userId, latestUserMessage);
        AgentToolContext toolContext = new AgentToolContext(userId, conversationId, runVersion);
        log.info("agent turn started conversation={} run={} toolCount={} memoryInjected={}",
                conversationId, runVersion, allowedTools.size(), !memoryContext.isEmpty());
        int maxSteps = Math.max(1, properties.getMaxSteps());
        int maxReflections = Math.max(0, properties.getMaxReflections());
        int reflections = 0;
        int failures = 0;
        JsonNode lastError = null;
        // 上一步自检的批评意见，只注入紧随其后的那一步。
        String critique = null;
        UsageAccumulator usage = new UsageAccumulator();

        for (int step = 0; step < maxSteps; step++) {
            // 用户取消请求优先于模型调用，工具执行结束后下一轮即退出。
            if (isCancelRequested(conversationId, userId, runVersion)) {
                String stopped = "好的，已停止当前任务。你可以继续说新的需求。";
                saveActiveMessage(conversationId, userId, runVersion, "assistant", "answer", null, stopped);
                appendActive(conversationId, userId, runVersion, "answer", stopped, listener);
                return finalizeReady(conversationId, userId, runVersion, usage, listener);
            }
            // 每一步重新读计划：智能体上一步可能刚改写过它，这是会话状态而不是一次性输入。
            List<AgentPlanItem> plan = planService.load(requireOwned(conversationId, userId));
            String context = buildContext(conversationId, userId, lastError, plan, memoryContext, critique);
            critique = null;
            long modelStartedNanos = System.nanoTime();
            AnswerProgress answerProgress = new AnswerProgress(
                    conversationId, userId, runVersion, listener,
                    step + 1, turnStartedNanos, modelStartedNanos);
            JsonNode decision;
            try {
                List<AgentAttachment> attachments = recentAttachments(conversationId, userId);
                ModelCallResult call = attachments.isEmpty()
                        ? modelClient.decideNextStepStream(
                                context, toolDescriptions, answerProgress::accept,
                                () -> isCancelRequested(conversationId, userId, runVersion))
                        : modelClient.decideNextStepStream(
                                context, toolDescriptions, attachments, answerProgress::accept,
                                () -> isCancelRequested(conversationId, userId, runVersion));
                answerProgress.flush();
                decision = call.payload();
                usage.add(call.usage(), call.model());
                log.info("agent model decision conversation={} run={} step={} durationMs={} done={}",
                        conversationId, runVersion, step + 1,
                        elapsedMillis(modelStartedNanos), decision.path("done").asBoolean(false));
            } catch (CancellationException exception) {
                String stopped = "好的，已停止当前任务。你可以继续说新的需求。";
                saveActiveMessage(conversationId, userId, runVersion, "assistant", "answer", null, stopped);
                appendActive(conversationId, userId, runVersion, "answer", stopped, listener);
                return finalizeReady(conversationId, userId, runVersion, usage, listener);
            }
            if (isCancelRequested(conversationId, userId, runVersion)) {
                String stopped = "好的，已停止当前任务。你可以继续说新的需求。";
                saveActiveMessage(conversationId, userId, runVersion, "assistant", "answer", null, stopped);
                appendActive(conversationId, userId, runVersion, "answer", stopped, listener);
                return finalizeReady(conversationId, userId, runVersion, usage, listener);
            }

            String thought = decision.path("thought").asText("");
            if (!decision.path("done").asBoolean(false) && StringUtils.hasText(thought)) {
                saveActiveMessage(conversationId, userId, runVersion, "assistant", "thought", null, thought);
                appendActive(conversationId, userId, runVersion, "thought", thought, listener);
            }

            if (decision.path("done").asBoolean(false)) {
                String reply = normalizeAnswer(defaultText(decision.path("reply").asText(), "已完成你的请求。"));
                Optional<String> verdict = turnEvaluator.critique(reply, plan, lastError);
                if (verdict.isPresent() && reflections < maxReflections) {
                    // 自检不通过：不把这次回复交给用户，把批评意见回灌让智能体自己纠偏。
                    reflections++;
                    critique = verdict.get();
                    log.info("agent turn reflection conversation={} run={} round={}",
                            conversationId, runVersion, reflections);
                    saveActiveMessage(conversationId, userId, runVersion,
                            "assistant", "reflection", null, critique);
                    appendActive(conversationId, userId, runVersion, "reflection",
                            JsonNodeFactory.instance.objectNode()
                                    .put("round", reflections)
                                    .put("critique", critique),
                            listener);
                    continue;
                }
                saveActiveMessage(conversationId, userId, runVersion, "assistant", "answer", null, reply);
                appendActive(conversationId, userId, runVersion, "answer", reply, listener);
                return finalizeReady(conversationId, userId, runVersion, usage, listener);
            }

            JsonNode action = decision.path("action");
            String toolName = action.path("tool").asText("");
            AgentTool tool = StringUtils.hasText(toolName) && allowedTools.contains(toolName)
                    ? toolRegistry.get(toolName) : null;
            if (tool == null) {
                lastError = JsonNodeFactory.instance.objectNode()
                        .put("ok", false)
                        .put("error", "未知工具 " + toolName + "，请从可用工具中选择")
                        .put("availableTools", String.join(", ", allowedTools));
                continue;
            }

            JsonNode args = action.path("args");
            ObjectNode call = JsonNodeFactory.instance.objectNode();
            call.put("tool", toolName);
            call.set("args", args);
            saveActiveMessage(conversationId, userId, runVersion, "tool", "tool_call", toolName, call.toString());
            appendActive(conversationId, userId, runVersion, "tool_start", call, listener);

            ToolProgress progress = new ToolProgress(conversationId, userId, runVersion, toolName, listener, usage);
            try {
                JsonNode result = tool.execute(toolContext, args, progress);
                progress.flushToolDelta();
                saveActiveMessage(conversationId, userId, runVersion, "tool", "tool_result", toolName, result.toString());
                appendActive(conversationId, userId, runVersion, "tool_end",
                        JsonNodeFactory.instance.objectNode().put("tool", toolName).set("result", result),
                        listener);
                // 计划变更单独发一个事件：前端要能把它当成"智能体的待办清单"渲染，而不是一段工具输出。
                if (PlanTasksTool.NAME.equals(toolName) && result.has("items")) {
                    appendActive(conversationId, userId, runVersion, "plan",
                            JsonNodeFactory.instance.objectNode().set("items", result.path("items")),
                            listener);
                }
                failures = 0;
                lastError = null;
            } catch (CancellationException exception) {
                progress.flushToolDelta();
                String stopped = "好的，已停止当前任务。你可以继续说新的需求。";
                saveActiveMessage(conversationId, userId, runVersion, "tool", "tool_result", toolName,
                        JsonNodeFactory.instance.objectNode()
                                .put("tool", toolName)
                                .put("cancelled", true)
                                .put("error", "已停止")
                                .toString());
                appendActive(conversationId, userId, runVersion, "tool_error",
                        JsonNodeFactory.instance.objectNode()
                                .put("tool", toolName)
                                .put("cancelled", true)
                                .put("error", "已停止"),
                        listener);
                saveActiveMessage(conversationId, userId, runVersion, "assistant", "answer", null, stopped);
                appendActive(conversationId, userId, runVersion, "answer", stopped, listener);
                return finalizeReady(conversationId, userId, runVersion, usage, listener);
            } catch (Exception e) {
                progress.flushToolDelta();
                failures++;
                ObjectNode errorNode = JsonNodeFactory.instance.objectNode();
                errorNode.put("ok", false);
                errorNode.put("tool", toolName);
                errorNode.put("error", safeMessage(e));
                saveActiveMessage(conversationId, userId, runVersion, "tool", "tool_result", toolName, errorNode.toString());
                appendActive(conversationId, userId, runVersion, "tool_error", errorNode, listener);
                if (failures >= Math.max(1, properties.getMaxToolFailures())) {
                    String reply = "很抱歉，连续多次尝试后仍然失败：" + safeMessage(e)
                            + "。请换个说法重试，或调整你的需求。";
                    saveActiveMessage(conversationId, userId, runVersion, "assistant", "answer", null, reply);
                    appendActive(conversationId, userId, runVersion, "answer", reply, listener);
                    return finalizeReady(conversationId, userId, runVersion, usage, listener);
                }
                lastError = errorNode;
            }
        }

        String exhausted = "这一步的推理步数已达上限，为避免资源消耗我先在这里暂停。"
                + "你可以继续补充信息或换一个更具体的需求。";
        saveActiveMessage(conversationId, userId, runVersion, "assistant", "answer", null, exhausted);
        appendActive(conversationId, userId, runVersion, "answer", exhausted, listener);
        return finalizeReady(conversationId, userId, runVersion, usage, listener);
    }

    private JsonNode progressPayload(String tool, String message) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("tool", tool);
        String raw = defaultText(message, "");
        int separator = raw.indexOf('|');
        if (separator > 0) {
            payload.put("stage", raw.substring(0, separator));
            payload.put("message", raw.substring(separator + 1));
        } else {
            payload.put("message", raw);
        }
        return payload;
    }

    private AgentConversation finalizeReady(Long conversationId, Long userId, long runVersion,
                                            UsageAccumulator usage, Consumer<AgentEvent> onEvent) {
        TerminalResult terminal = transactionTemplate.execute(status -> {
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
                    .set(AgentConversation::getUpdatedAt, now));
            if (updated != 1) {
                throw new AgentRunSupersededException(conversationId, runVersion);
            }
            current.setStatus("ready");
            current.setErrorMessage(null);
            current.setCancelRequested(0);
            current.setUpdatedAt(now);
            AgentEvent event = eventService.storeForRun(
                    conversationId, userId, runVersion, "complete", current, false);
            return new TerminalResult(current, event);
        });
        billingService.chargeTokens(userId, BillingBiz.AGENT_TURN,
                "conv:" + conversationId + ":run:" + runVersion, "conv:" + conversationId,
                usage.sum(), usage.model(), "智能体会话单轮执行");
        eventService.deliver(terminal.event(), onEvent);
        return terminal.conversation();
    }

    /** 请求停止当前正在执行的一轮；会话空闲时幂等返回。 */
    public AgentConversation cancel(Long conversationId, Long userId) {
        AgentConversation current = requireOwned(conversationId, userId);
        long runVersion = current.getRunVersion() == null ? 0L : current.getRunVersion();
        transactionTemplate.executeWithoutResult(status -> conversationMapper.update(
                null, new LambdaUpdateWrapper<AgentConversation>()
                        .eq(AgentConversation::getId, conversationId)
                        .eq(AgentConversation::getUserId, userId)
                        .eq(AgentConversation::getStatus, "running")
                        .eq(AgentConversation::getRunVersion, runVersion)
                        .set(AgentConversation::getCancelRequested, 1)
                        .set(AgentConversation::getUpdatedAt, LocalDateTime.now())));
        return requireOwned(conversationId, userId);
    }

    private boolean isCancelRequested(Long conversationId, Long userId, long runVersion) {
        AgentConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !userId.equals(conversation.getUserId())
                || !Long.valueOf(runVersion).equals(conversation.getRunVersion())
                || !"running".equals(conversation.getStatus())) {
            throw new AgentRunSupersededException(conversationId, runVersion);
        }
        return Integer.valueOf(1).equals(conversation.getCancelRequested());
    }

    /** Keeps a claimed worker alive even while a model or tool call emits no durable progress. */
    private ScheduledFuture<?> startLeaseRenewal(Long conversationId, Long userId, long runVersion) {
        long staleSeconds = Math.max(60L, Math.max(1, properties.getStaleConversationMinutes()) * 60L);
        long intervalSeconds = Math.max(1L, Math.min(MAX_LEASE_RENEW_INTERVAL_SECONDS, staleSeconds / 3L));
        return agentLeaseRenewer.scheduleAtFixedRate(() -> {
            try {
                int renewed = conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                        .eq(AgentConversation::getId, conversationId)
                        .eq(AgentConversation::getUserId, userId)
                        .eq(AgentConversation::getStatus, "running")
                        .eq(AgentConversation::getRunVersion, runVersion)
                        .set(AgentConversation::getUpdatedAt, LocalDateTime.now()));
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

    private long claimConversation(Long conversationId, Long userId) {
        int updated = conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                .eq(AgentConversation::getId, conversationId)
                .eq(AgentConversation::getUserId, userId)
                // failed 是上一轮模型/工具失败留下的状态，允许用户重试新一轮。
                .and(wrapper -> wrapper.eq(AgentConversation::getStatus, "ready")
                        .or()
                        .eq(AgentConversation::getStatus, "failed"))
                .set(AgentConversation::getStatus, "running")
                .setSql("run_version = run_version + 1")
                .set(AgentConversation::getErrorMessage, null)
                .set(AgentConversation::getCancelRequested, 0)
                .set(AgentConversation::getUpdatedAt, LocalDateTime.now()));
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

    private AgentEvent markFailed(Long conversationId, Long userId, long runVersion, String errorMessage) {
        return transactionTemplate.execute(status -> {
            String safeError = limit(defaultText(errorMessage, "智能体任务失败，请稍后重试"), 1000);
            int updated = conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                    .eq(AgentConversation::getId, conversationId)
                    .eq(AgentConversation::getUserId, userId)
                    .eq(AgentConversation::getStatus, "running")
                    .eq(AgentConversation::getRunVersion, runVersion)
                    .set(AgentConversation::getStatus, "failed")
                    .set(AgentConversation::getErrorMessage, safeError)
                    .set(AgentConversation::getUpdatedAt, LocalDateTime.now()));
            if (updated != 1) {
                throw new AgentRunSupersededException(conversationId, runVersion);
            }
            return eventService.storeForRun(
                    conversationId, userId, runVersion, "error", safeError, false);
        });
    }

    /** 中断(超过 stale 分钟仍 running)的会话恢复为 failed，保留可重试语义与错误终态。 */
    public AgentConversation resolveStale(AgentConversation conversation, Long userId) {
        if (!"running".equals(conversation.getStatus()) || conversation.getUpdatedAt() == null) {
            return conversation;
        }
        int staleMinutes = Math.max(1, properties.getStaleConversationMinutes());
        if (conversation.getUpdatedAt().isAfter(LocalDateTime.now().minusMinutes(staleMinutes))) {
            return conversation;
        }
        int updated = transactionTemplate.execute(status -> {
            String interrupted = "上一轮执行中断，可继续对话";
            int changed = conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                    .eq(AgentConversation::getId, conversation.getId())
                    .eq(AgentConversation::getUserId, userId)
                    .eq(AgentConversation::getStatus, "running")
                    .eq(AgentConversation::getRunVersion, conversation.getRunVersion())
                    .le(AgentConversation::getUpdatedAt, LocalDateTime.now().minusMinutes(staleMinutes))
                    .set(AgentConversation::getStatus, "failed")
                    .set(AgentConversation::getErrorMessage, interrupted)
                    .set(AgentConversation::getUpdatedAt, LocalDateTime.now()));
            long runVersion = conversation.getRunVersion() == null ? 0L : conversation.getRunVersion();
            if (changed == 1 && eventService.latestTerminal(conversation.getId(), runVersion) == null) {
                eventService.storeForRun(
                        conversation.getId(), userId, runVersion, "error", interrupted, false);
            }
            return changed;
        });
        // A zero-row stale transition means another worker/request won the race; return current durable state.
        return requireOwned(conversation.getId(), userId);
    }

    public AgentConversation get(Long conversationId, Long userId) {
        return resolveStale(requireOwned(conversationId, userId), userId);
    }

    public List<AgentMessage> listMessages(Long conversationId, Long userId) {
        requireOwned(conversationId, userId);
        return hydrateAttachments(messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getConversationId, conversationId)
                .orderByAsc(AgentMessage::getId)));
    }

    public List<AgentConversation> listSessions(Long userId) {
        return conversationMapper.selectList(new LambdaQueryWrapper<AgentConversation>()
                .eq(AgentConversation::getUserId, userId)
                .orderByDesc(AgentConversation::getUpdatedAt)
                .last("LIMIT 50"));
    }

    /** 启用分享：生成唯一 share_token 并返回。若已启用则直接返回现有 token。 */
    public String enableShare(Long conversationId, Long userId) {
        AgentConversation conversation = requireOwned(conversationId, userId);
        if (conversation.getShareToken() != null) {
            return conversation.getShareToken();
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                .eq(AgentConversation::getId, conversationId)
                .eq(AgentConversation::getUserId, userId)
                .set(AgentConversation::getShareToken, token)
                .set(AgentConversation::getUpdatedAt, LocalDateTime.now()));
        return token;
    }

    /** 禁用分享：将 share_token 置 NULL。 */
    public void disableShare(Long conversationId, Long userId) {
        requireOwned(conversationId, userId);
        conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                .eq(AgentConversation::getId, conversationId)
                .eq(AgentConversation::getUserId, userId)
                .set(AgentConversation::getShareToken, null)
                .set(AgentConversation::getUpdatedAt, LocalDateTime.now()));
    }

    /** 通过分享令牌获取对话（公开接口，无需登录）。 */
    public AgentConversation getByShareToken(String shareToken) {
        if (!StringUtils.hasText(shareToken)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分享链接无效");
        }
        AgentConversation conversation = conversationMapper.selectOne(
                new LambdaQueryWrapper<AgentConversation>()
                        .eq(AgentConversation::getShareToken, shareToken));
        if (conversation == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分享链接无效或已失效");
        }
        return conversation;
    }

    /** 通过分享令牌获取消息列表（公开接口，无需登录）。 */
    public List<AgentMessage> listMessagesByShareToken(String shareToken) {
        AgentConversation conversation = getByShareToken(shareToken);
        return hydrateAttachments(messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getConversationId, conversation.getId())
                .orderByAsc(AgentMessage::getId)));
    }

    private void saveMessage(Long conversationId, String role, String kind,
                             String toolName, String content) {
        saveMessage(conversationId, role, kind, toolName, content, null);
    }

    private void saveMessage(Long conversationId, String role, String kind,
                             String toolName, String content, String attachmentsJson) {
        AgentMessage message = new AgentMessage();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setKind(kind);
        message.setToolName(toolName);
        message.setContent(content);
        message.setAttachmentsJson(attachmentsJson);
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);
    }

    private void saveActiveMessage(Long conversationId, Long userId, long runVersion,
                                   String role, String kind, String toolName, String content) {
        AgentMessage message = new AgentMessage();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setKind(kind);
        message.setToolName(toolName);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.now());
        transactionTemplate.executeWithoutResult(status -> {
            int touched = conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                    .eq(AgentConversation::getId, conversationId)
                    .eq(AgentConversation::getUserId, userId)
                    .eq(AgentConversation::getStatus, "running")
                    .eq(AgentConversation::getRunVersion, runVersion)
                    .set(AgentConversation::getUpdatedAt, LocalDateTime.now()));
            if (touched != 1 || messageMapper.insertForActiveRun(message, userId, runVersion) != 1) {
                throw new AgentRunSupersededException(conversationId, runVersion);
            }
        });
    }

    private AgentEvent appendActive(Long conversationId, Long userId, long runVersion,
                                    String eventType, Object data, Consumer<AgentEvent> listener) {
        AgentEvent event = eventService.storeActive(conversationId, userId, runVersion, eventType, data);
        eventService.deliver(event, listener);
        return event;
    }

    /**
     * 拼装模型上下文：长期记忆、任务计划、对话历史、最近失败、自检批评。
     *
     * <p>计划和记忆放在历史之前，因为它们是"我是谁、我打算怎么做"这类基线状态；
     * 批评意见放在最后，紧贴决策点，避免被历史冲淡。</p>
     */
    private String buildContext(Long conversationId, Long userId, JsonNode lastError,
                               List<AgentPlanItem> plan, String memoryContext, String critique) {
        List<AgentMessage> messages = listMessages(conversationId, userId);
        int limit = Math.max(5, properties.getContextMessageLimit());
        List<AgentMessage> recent = messages.size() <= limit
                ? messages
                : messages.subList(messages.size() - limit, messages.size());
        int lastToolResultIndex = lastToolResultIndex(recent);

        StringBuilder builder = new StringBuilder();
        if (StringUtils.hasText(memoryContext)) {
            builder.append("以下是你对这位用户的长期记忆（跨会话有效，可直接使用；与用户当前说法冲突时以用户当前说法为准）：\n")
                    .append(memoryContext).append("\n\n");
        }
        String renderedPlan = planService.render(plan);
        if (StringUtils.hasText(renderedPlan)) {
            builder.append("以下是你自己写下的任务计划（会话状态，用 plan_tasks 改写它）：\n")
                    .append(renderedPlan).append("\n\n");
        }
        builder.append("以下是智能体与用户的对话记录：\n\n");
        for (int index = 0; index < recent.size(); index++) {
            AgentMessage message = recent.get(index);
            String role = switch (message.getRole()) {
                case "user" -> "用户";
                case "tool" -> "工具结果";
                default -> "智能体";
            };
            String content = switch (message.getKind()) {
                case "thought" -> "（思考：）" + message.getContent();
                case "reflection" -> "（自检：）" + message.getContent();
                case "tool_call" -> "（调用了工具）" + message.getContent();
                case "tool_result" -> compactJson(message.getContent(), index == lastToolResultIndex
                        ? TOOL_RESULT_LATEST_CHARS : TOOL_RESULT_HISTORY_CHARS);
                case "answer" -> "（回复：）" + message.getContent();
                default -> message.getContent();
            };
            builder.append("【").append(role).append("】").append(content).append("\n\n");
            if (message.getAttachments() != null && !message.getAttachments().isEmpty()) {
                builder.append("【附件】")
                        .append(message.getAttachments().stream().map(AgentAttachment::name).toList())
                        .append("\n\n");
            }
        }
        if (lastError != null) {
            builder.append("【最近一次工具失败】").append(lastError.toString()).append("\n\n");
        }
        if (StringUtils.hasText(critique)) {
            builder.append("【自检结果】").append(critique).append("\n\n");
        }
        builder.append("请根据以上信息决定下一步：需要动手就返回 action，已经真正做完就返回 done=true 的最终回复。");
        return builder.toString();
    }

    /** 最近一条工具结果在窗口中的下标；没有则返回 -1。 */
    private int lastToolResultIndex(List<AgentMessage> recent) {
        for (int index = recent.size() - 1; index >= 0; index--) {
            if ("tool_result".equals(recent.get(index).getKind())) return index;
        }
        return -1;
    }

    /** 召回与本轮输入相关的长期记忆；记忆是增益而非必需，失败不能拖垮整轮对话。 */
    private String recallMemoryContext(Long userId, String latestUserMessage) {
        try {
            List<AgentMemory> memories = memoryService.recall(
                    userId, latestUserMessage, AgentMemoryService.RECALL_LIMIT);
            return defaultText(memoryService.render(memories), "");
        } catch (RuntimeException exception) {
            log.warn("agent memory recall failed, userId={}", userId, exception);
            return "";
        }
    }

    private String latestUserMessage(Long conversationId, Long userId) {
        List<AgentMessage> messages = listMessages(conversationId, userId);
        for (int index = messages.size() - 1; index >= 0; index--) {
            AgentMessage message = messages.get(index);
            if ("user".equals(message.getRole()) && "message".equals(message.getKind())) {
                return message.getContent();
            }
        }
        return "";
    }

    private List<AgentAttachment> recentAttachments(Long conversationId, Long userId) {
        List<AgentMessage> messages = listMessages(conversationId, userId);
        java.util.LinkedHashMap<String, AgentAttachment> recent = new java.util.LinkedHashMap<>();
        for (int index = messages.size() - 1; index >= 0; index--) {
            AgentMessage message = messages.get(index);
            if (!"user".equals(message.getRole()) || message.getAttachments() == null) continue;
            for (AgentAttachment attachment : message.getAttachments()) {
                recent.putIfAbsent(attachment.url(), attachment);
                if (recent.size() >= 5) return reverseAttachments(recent);
            }
        }
        return reverseAttachments(recent);
    }

    private List<AgentAttachment> reverseAttachments(
            java.util.LinkedHashMap<String, AgentAttachment> attachments) {
        java.util.ArrayList<AgentAttachment> ordered = new java.util.ArrayList<>(attachments.values());
        java.util.Collections.reverse(ordered);
        return List.copyOf(ordered);
    }

    private String serializeAttachments(List<AgentAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) return null;
        if (attachments.size() > 5) throw new IllegalArgumentException("每次最多上传5个附件");
        try {
            return objectMapper.writeValueAsString(attachments);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("附件信息无效", exception);
        }
    }

    private List<AgentMessage> hydrateAttachments(List<AgentMessage> messages) {
        for (AgentMessage message : messages) {
            if (!StringUtils.hasText(message.getAttachmentsJson())) {
                message.setAttachments(List.of());
                continue;
            }
            try {
                message.setAttachments(objectMapper.readValue(
                        message.getAttachmentsJson(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, AgentAttachment.class)));
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                log.warn("Unable to deserialize agent message attachments, messageId={}", message.getId());
                message.setAttachments(List.of());
            }
        }
        return messages;
    }

    private long elapsedMillis(long startedNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private String compactJson(String content, int maxLength) {
        if (!StringUtils.hasText(content)) return "";
        return content.length() <= maxLength ? content : content.substring(0, maxLength) + "…";
    }

    private AgentConversation requireOwned(Long conversationId, Long userId) {
        AgentConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || userId == null || !userId.equals(conversation.getUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "智能体会话不存在");
        }
        return conversation;
    }

    private String safeMessage(Exception e) {
        if (e instanceof ResponseStatusException response && response.getReason() != null) {
            return response.getReason();
        }
        if (e instanceof IllegalArgumentException || e instanceof CancellationException) {
            return StringUtils.hasText(e.getMessage()) ? e.getMessage() : "请求未完成";
        }
        if (e instanceof IllegalStateException && StringUtils.hasText(e.getMessage())
                && !e.getMessage().contains("http") && !e.getMessage().contains("HTTP")) {
            return e.getMessage();
        }
        return "智能体任务失败，请稍后重试";
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private boolean isDefaultTitle(String title) {
        return !StringUtils.hasText(title) || DEFAULT_TITLE.equals(title);
    }

    private String excerpt(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length - 1) + "…";
    }

    private String limit(String value, int maxLength) {
        String safe = value == null ? "" : value;
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
    }

    /** Coalesces model token callbacks into bounded durable tool events and preserves event order. */
    private final class ToolProgress implements ProgressListener {
        private final Long conversationId;
        private final Long userId;
        private final long runVersion;
        private final String toolName;
        private final Consumer<AgentEvent> listener;
        private final UsageAccumulator usage;
        private final StringBuilder deltaBuffer = new StringBuilder();

        private ToolProgress(Long conversationId, Long userId, long runVersion,
                             String toolName, Consumer<AgentEvent> listener, UsageAccumulator usage) {
            this.conversationId = conversationId;
            this.userId = userId;
            this.runVersion = runVersion;
            this.toolName = toolName;
            this.listener = listener;
            this.usage = usage;
        }

        @Override
        public void onProgress(String message) {
            flushToolDelta();
            appendActive(conversationId, userId, runVersion, "tool_progress",
                    progressPayload(toolName, message), listener);
        }

        @Override
        public void onToolDelta(String delta) {
            if (delta == null || delta.isEmpty()) return;
            deltaBuffer.append(delta);
            if (deltaBuffer.length() >= TOOL_DELTA_FLUSH_CHARS) {
                flushToolDelta();
            }
        }

        /** 工具（子智能体）自己调模型烧掉的 token 也算本轮成本，否则这部分绕过计费。 */
        @Override
        public void onModelUsage(ModelUsage modelUsage, String usedModel) {
            usage.add(modelUsage, usedModel);
        }

        @Override
        public boolean isCancellationRequested() {
            return AgentConversationService.this.isCancelRequested(conversationId, userId, runVersion);
        }

        private void flushToolDelta() {
            if (deltaBuffer.isEmpty()) return;
            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.put("tool", toolName);
            payload.put("delta", deltaBuffer.toString());
            deltaBuffer.setLength(0);
            appendActive(conversationId, userId, runVersion, "tool_delta", payload, listener);
        }
    }

    /** Pushes the first reply delta immediately, then batches tiny token deltas to reduce database round trips. */
    private final class AnswerProgress {
        private final Long conversationId;
        private final Long userId;
        private final long runVersion;
        private final Consumer<AgentEvent> listener;
        private final int step;
        private final long turnStartedNanos;
        private final long modelStartedNanos;
        private final StringBuilder deltaBuffer = new StringBuilder();
        private boolean firstDeltaDelivered;

        private AnswerProgress(Long conversationId, Long userId, long runVersion,
                               Consumer<AgentEvent> listener, int step,
                               long turnStartedNanos, long modelStartedNanos) {
            this.conversationId = conversationId;
            this.userId = userId;
            this.runVersion = runVersion;
            this.listener = listener;
            this.step = step;
            this.turnStartedNanos = turnStartedNanos;
            this.modelStartedNanos = modelStartedNanos;
        }

        private void accept(String delta) {
            if (delta == null || delta.isEmpty()) return;
            String normalized = normalizeAnswer(delta);
            if (!firstDeltaDelivered) {
                firstDeltaDelivered = true;
                appendActive(conversationId, userId, runVersion, "answer_delta",
                        normalized, listener);
                log.info("agent first visible delta conversation={} run={} step={} modelMs={} turnMs={}",
                        conversationId, runVersion, step,
                        elapsedMillis(modelStartedNanos), elapsedMillis(turnStartedNanos));
                return;
            }
            deltaBuffer.append(normalized);
            if (deltaBuffer.length() >= ANSWER_DELTA_FLUSH_CHARS) flush();
        }

        private void flush() {
            if (deltaBuffer.isEmpty()) return;
            appendActive(conversationId, userId, runVersion, "answer_delta",
                    deltaBuffer.toString(), listener);
            deltaBuffer.setLength(0);
        }
    }

    private record TerminalResult(AgentConversation conversation, AgentEvent event) {
    }

    /** Aggregates token usage across all decideNextStep calls of one conversation turn. */
    private static final class UsageAccumulator {
        private long input;
        private long cached;
        private long output;
        private String model;
        private boolean mixedModels;

        void add(ModelUsage usage, String usedModel) {
            if (usage == null) return;
            input += Math.max(0L, usage.inputTokens());
            cached += Math.max(0L, usage.cachedTokens());
            output += Math.max(0L, usage.outputTokens());
            if (StringUtils.hasText(usedModel)) {
                if (model == null) model = usedModel;
                else if (!model.equals(usedModel)) mixedModels = true;
            }
        }

        ModelUsage sum() {
            return new ModelUsage(input, cached, output);
        }

        String model() {
            return mixedModels ? null : model;
        }
    }
}
