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
import com.xander.lab.agent.domain.AgentStep;
import com.xander.lab.agent.domain.AgentToolInvocation;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import com.xander.lab.agent.infrastructure.AgentConversationProperties;
import com.xander.lab.agent.infrastructure.AgentMessageMapper;
import com.xander.lab.agent.infrastructure.AgentModelClient;
import com.xander.lab.billing.application.BillingService;
import com.xander.lab.billing.domain.BillingBiz;
import com.xander.lab.common.ModelCallResult;
import com.xander.lab.common.ModelUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
public class AgentConversationService {
    private static final int TOOL_DELTA_FLUSH_CHARS = 256;
    private static final int ANSWER_DELTA_FLUSH_CHARS = 24;
    private static final String DEFAULT_TITLE = "新对话";
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
    private final BillingService billingService;
    private final ObjectMapper objectMapper;
    private final AgentPlanService planService;
    private final AgentMemoryService memoryService;
    private final AgentQuizService quizService;
    private final AgentExecutionJournal executionJournal;
    private final AgentRequestAssembler requestAssembler;
    private final AgentRunLifecycle runLifecycle;
    private final AgentEventProjector eventProjector;
    private final AgentTurnCoordinator turnCoordinator;

    @Autowired
    public AgentConversationService(AgentConversationMapper conversationMapper, AgentMessageMapper messageMapper,
                                    AgentModelClient modelClient, AgentToolRegistry toolRegistry,
                                    AgentConversationProperties properties, TransactionTemplate transactionTemplate,
                                    AgentEventService eventService,
                                    BillingService billingService, ObjectMapper objectMapper,
                                    AgentPlanService planService, AgentMemoryService memoryService,
                                    AgentQuizService quizService,
                                    AgentExecutionJournal executionJournal,
                                    AgentRequestAssembler requestAssembler,
                                    AgentRunLifecycle runLifecycle, AgentEventProjector eventProjector,
                                    AgentTurnCoordinator turnCoordinator) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.eventService = eventService;
        this.billingService = billingService;
        this.objectMapper = objectMapper;
        this.planService = planService;
        this.memoryService = memoryService;
        this.quizService = quizService;
        this.executionJournal = executionJournal;
        this.requestAssembler = requestAssembler;
        this.runLifecycle = runLifecycle;
        this.eventProjector = eventProjector;
        this.turnCoordinator = turnCoordinator;
    }

    /** Compatibility constructor retained for focused loop tests and embedded callers. */
    public AgentConversationService(AgentConversationMapper conversationMapper, AgentMessageMapper messageMapper,
                                    AgentModelClient modelClient, AgentToolRegistry toolRegistry,
                                    AgentConversationProperties properties, TransactionTemplate transactionTemplate,
                                    AgentEventService eventService, ScheduledExecutorService agentLeaseRenewer,
                                    BillingService billingService, ObjectMapper objectMapper,
                                    AgentPlanService planService, AgentMemoryService memoryService,
                                    AgentQuizService quizService) {
        this(conversationMapper, messageMapper, modelClient, toolRegistry, properties, transactionTemplate,
                eventService, billingService, objectMapper, planService, memoryService,
                quizService, AgentExecutionJournal.disabled(),
                AgentRequestAssembler.legacy(toolRegistry, properties, planService, quizService, objectMapper),
                new AgentRunLifecycle(conversationMapper, eventService, AgentExecutionJournal.disabled(),
                        properties, transactionTemplate, agentLeaseRenewer),
                new AgentEventProjector(conversationMapper, messageMapper, eventService, transactionTemplate),
                new AgentTurnCoordinator(conversationMapper, eventService,
                        new AgentRunLifecycle(conversationMapper, eventService, AgentExecutionJournal.disabled(),
                                properties, transactionTemplate, agentLeaseRenewer),
                        AgentExecutionJournal.disabled(), AgentStepExecutor.legacy(modelClient)));
    }

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
        conversation.setIsPinned(0);
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
        // 答题卡交上来的是一段 JSON。在入口就翻译成可读的作答记录：历史、上下文窗口、
        // 分享页此后只需要认识纯文本，不必各自再解一遍这个内部格式。
        Optional<String> answers = quizService.renderSubmission(userMessage,
                () -> listMessages(conversationId, userId));
        String storedMessage = answers.orElse(userMessage);
        String storedKind = answers.isPresent() ? "quiz_answer" : "message";
        return transactionTemplate.execute(status -> {
            long runVersion = runLifecycle.claim(conversationId, userId);
            executionJournal.startRun(conversationId, userId, runVersion);
            // 新一轮诉求开始：上一轮的计划已经收口或作废，留着只会污染这一轮的自检。
            planService.clear(conversationId, userId);
            saveMessage(conversationId, "user", storedKind, null, storedMessage,
                    serializeAttachments(attachments));
            AgentConversation claimed = requireOwned(conversationId, userId);
            // 一次作答不是新诉求，不该顶掉会话标题——那样标题会变成"测验会话 id=…"。
            if (isDefaultTitle(claimed.getTitle()) && answers.isEmpty()) {
                claimed.setTitle(excerpt(storedMessage, 40));
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
        return executeClaimedTurn(conversationId, userId, runVersion, currentUserMessage, false, onEvent);
    }

    /** Runs a claimed turn; deep-thinking metadata is retained without self-check retries. */
    public AgentConversation executeClaimedTurn(Long conversationId, Long userId, long runVersion,
                                                String currentUserMessage, boolean deepThinking,
                                                Consumer<AgentEvent> onEvent) {
        return turnCoordinator.execute(conversationId, userId, runVersion, onEvent,
                () -> runLoop(conversationId, userId, runVersion, currentUserMessage, deepThinking, onEvent));
    }

    /** Persists a terminal error when a claimed turn cannot be submitted to the worker pool. */
    public void failClaimedTurn(Long conversationId, Long userId, long runVersion, String message) {
        turnCoordinator.failBeforeStart(conversationId, userId, runVersion, message, null);
    }

    /** Convenience entry point used by non-HTTP callers: claim, persist input, then execute. */
    public AgentConversation executeTurn(Long conversationId, Long userId, String message,
        Consumer<AgentEvent> onEvent) {
        return executeTurn(conversationId, userId, message, false, onEvent);
    }

    /** Convenience entry point that also chooses the self-check depth for this turn. */
    public AgentConversation executeTurn(Long conversationId, Long userId, String message,
        boolean deepThinking, Consumer<AgentEvent> onEvent) {
        AgentConversation claimed = prepareTurn(conversationId, userId, message);
        return executeClaimedTurn(conversationId, userId, claimed.getRunVersion(), message,
                deepThinking, onEvent);
    }

    private AgentConversation runLoop(Long conversationId, Long userId, long runVersion,
                                      String currentUserMessage, boolean deepThinking,
                                      Consumer<AgentEvent> listener) {
        long turnStartedNanos = System.nanoTime();
        // 答题卡提交是一段 JSON，拿它去召回记忆只会命中噪声；这种轮次退回上一条真实诉求。
        String latestUserMessage = StringUtils.hasText(currentUserMessage)
                && !quizService.isSubmission(currentUserMessage)
                ? currentUserMessage : latestUserMessage(conversationId, userId);
        // 工具表不再按关键词预筛：用不用、用哪个由模型自己判断。
        // 旧的关键词门禁会让匹配不上词表的诉求连工具都看不见，只能空口答应，那才是"工作流"。
        Set<String> allowedTools = toolRegistry.allowedNames();
        String memoryContext = recallMemoryContext(userId, latestUserMessage);
        log.info("agent turn started conversation={} run={} toolCount={} memoryInjected={}",
                conversationId, runVersion, allowedTools.size(), !memoryContext.isEmpty());
        int maxSteps = Math.max(1, properties.getMaxSteps());
        int failures = 0;
        JsonNode lastError = null;
        UsageAccumulator usage = new UsageAccumulator();

        for (int step = 0; step < maxSteps; step++) {
            // 用户取消请求优先于模型调用，工具执行结束后下一轮即退出。
            if (runLifecycle.isCancellationRequested(conversationId, userId, runVersion)) {
                String stopped = "好的，已停止当前任务。你可以继续说新的需求。";
                saveActiveMessage(conversationId, userId, runVersion, "assistant", "answer", null, stopped);
                appendActive(conversationId, userId, runVersion, "answer", stopped, listener);
                return finalizeReady(conversationId, userId, runVersion, usage, listener);
            }
            // 每一步重新读计划：智能体上一步可能刚改写过它，这是会话状态而不是一次性输入。
            List<AgentPlanItem> plan = planService.load(requireOwned(conversationId, userId));
            List<AgentMessage> messages = listMessages(conversationId, userId);
            AgentStepRequest request = requestAssembler.assemble(new AgentRequestAssemblyInput(
                    conversationId, userId, runVersion, step + 1, messages, plan, memoryContext,
                    lastError, null, allowedTools, maxSteps - step, deepThinking));
            long modelStartedNanos = System.nanoTime();
            AnswerProgress answerProgress = new AnswerProgress(
                    conversationId, userId, runVersion, listener,
                    step + 1, turnStartedNanos, modelStartedNanos,
                    true);
            JsonNode decision;
            AgentStep journalStep;
            try {
                ExecutedModelStep executedStep = turnCoordinator.executeModelStep(request, answerProgress::accept,
                        () -> runLifecycle.isCancellationRequested(conversationId, userId, runVersion));
                ModelCallResult call = executedStep.result();
                journalStep = executedStep.step();
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
            } catch (RuntimeException exception) {
                throw exception;
            }
            if (runLifecycle.isCancellationRequested(conversationId, userId, runVersion)) {
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
                saveActiveMessage(conversationId, userId, runVersion, "assistant", "answer", null, reply);
                appendActive(conversationId, userId, runVersion, "answer", reply, listener);
                return finalizeReady(conversationId, userId, runVersion, usage, listener);
            }

            JsonNode action = decision.path("action");
            String toolName = action.path("tool").asText("");
            AgentTool tool = StringUtils.hasText(toolName) && allowedTools.contains(toolName)
                    ? toolRegistry.get(toolName) : null;
            if (tool == null) {
                // 点名一个不存在的工具是协议错误，但同样要计入失败预算。
                // 不计数的话，模型可以一直点名错工具：每一步都被静默跳过，整轮步数烧完，
                // 用户等了很久却只收到一句"步数已达上限"，看不出到底发生了什么。
                failures++;
                lastError = JsonNodeFactory.instance.objectNode()
                        .put("ok", false)
                        .put("error", "未知工具 " + describeTool(toolName) + "，请从可用工具中选择")
                        .put("availableTools", String.join(", ", allowedTools));
                if (failures >= Math.max(1, properties.getMaxToolFailures())) {
                    String reply = "很抱歉，我连续几次都没选对可用的工具（" + describeTool(toolName)
                            + " 不存在），这一轮先停在这里。请把需求说得更具体一些，或换个说法再试。";
                    saveActiveMessage(conversationId, userId, runVersion, "assistant", "answer", null, reply);
                    appendActive(conversationId, userId, runVersion, "answer", reply, listener);
                    return finalizeReady(conversationId, userId, runVersion, usage, listener);
                }
                continue;
            }

            JsonNode args = action.path("args");
            String invocationId = UUID.randomUUID().toString();
            AgentToolInvocation invocation = executionJournal.startInvocation(
                    journalStep, invocationId, toolName, args);
            AgentToolContext toolContext = new AgentToolContext(userId, conversationId, runVersion, invocationId);
            ObjectNode call = JsonNodeFactory.instance.objectNode();
            call.put("tool", toolName);
            call.put("invocationId", invocationId);
            call.set("args", args);
            saveActiveMessage(conversationId, userId, runVersion, "tool", "tool_call", toolName, call.toString());
            appendActive(conversationId, userId, runVersion, "tool_start", call, listener);

            ToolProgress progress = new ToolProgress(conversationId, userId, runVersion, toolName, listener, usage);
            try {
                JsonNode result = tool.execute(toolContext, args, progress);
                progress.flushToolDelta();
                executionJournal.completeInvocation(invocation, result);
                saveActiveMessage(conversationId, userId, runVersion, "tool", "tool_result", toolName, result.toString());
                appendActive(conversationId, userId, runVersion, "tool_end",
                        JsonNodeFactory.instance.objectNode().put("tool", toolName).set("result", result),
                        listener);
                // 计划变更单独发一个事件：前端要能把它当成"智能体的待办清单"渲染，而不是一段工具输出。
                if (PlanTasksTool.NAME.equals(toolName) && result.has("items")) {
                    saveOrUpdateActivePlanMessage(conversationId, userId, runVersion,
                            result.path("items").toString());
                    appendActive(conversationId, userId, runVersion, "plan",
                            JsonNodeFactory.instance.objectNode().set("items", result.path("items")),
                            listener);
                }
                // 答题卡要落成一条消息而不只是一个事件：它是用户接下来要操作的界面，
                // 刷新页面后必须还在，而事件只在本轮的流里活着。
                if (EmitQuizTool.NAME.equals(toolName) && result.has(EmitQuizTool.QUIZ_FIELD)) {
                    JsonNode quiz = result.path(EmitQuizTool.QUIZ_FIELD);
                    saveActiveMessage(conversationId, userId, runVersion,
                            "assistant", "quiz", null, quiz.toString());
                    appendActive(conversationId, userId, runVersion, "quiz", quiz, listener);
                }
                // 交付卡同理：代码是这一轮真正的产出，必须落成消息，刷新页面后还能拿到源码。
                if (EmitArtifactTool.NAME.equals(toolName) && result.has(EmitArtifactTool.ARTIFACT_FIELD)) {
                    JsonNode artifact = result.path(EmitArtifactTool.ARTIFACT_FIELD);
                    saveActiveMessage(conversationId, userId, runVersion,
                            "assistant", "artifact", null, artifact.toString());
                    appendActive(conversationId, userId, runVersion, "artifact", artifact, listener);
                }
                failures = 0;
                lastError = null;
            } catch (CancellationException exception) {
                progress.flushToolDelta();
                executionJournal.cancelInvocation(invocation);
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
                log.error("agent tool execution failed conversationId={} userId={} runVersion={} phase=tool.execute tool={} exceptionType={} message={}",
                        conversationId, userId, runVersion, toolName, e.getClass().getName(), e.getMessage(), e);
                progress.flushToolDelta();
                executionJournal.failInvocation(invocation, safeMessage(e));
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
        return eventProjector.progressPayload(tool, message);
    }

    private AgentConversation finalizeReady(Long conversationId, Long userId, long runVersion,
                                            UsageAccumulator usage, Consumer<AgentEvent> onEvent) {
        TerminalTransition terminal = runLifecycle.complete(conversationId, userId, runVersion);
        billingService.chargeTokens(userId, BillingBiz.AGENT_TURN,
                "conv:" + conversationId + ":run:" + runVersion, "conv:" + conversationId,
                usage.sum(), usage.model(), "智能体会话单轮执行");
        eventService.deliver(terminal.event(), onEvent);
        return terminal.conversation();
    }

    /** 请求停止当前正在执行的一轮；会话空闲时幂等返回。 */
    public AgentConversation cancel(Long conversationId, Long userId) {
        return runLifecycle.requestCancellation(conversationId, userId);
    }

    private AgentEvent markFailed(Long conversationId, Long userId, long runVersion, String errorMessage) {
        return runLifecycle.fail(conversationId, userId, runVersion, errorMessage);
    }

    /** 中断(超过 stale 分钟仍 running)的会话恢复为 failed，保留可重试语义与错误终态。 */
    public AgentConversation resolveStale(AgentConversation conversation, Long userId) {
        runLifecycle.recoverStale(conversation, userId);
        return requireOwned(conversation.getId(), userId);
    }

    public AgentConversation get(Long conversationId, Long userId) {
        return withUnreadFlag(resolveStale(requireOwned(conversationId, userId), userId));
    }

    /**
     * 打开会话即视为已读，清掉列表上的"生成完成"提醒。
     *
     * <p>只写 last_read_at 并显式回写 updated_at：updated_at 带 ON UPDATE CURRENT_TIMESTAMP，
     * 若不显式回写，单纯翻看历史会话就会把它顶到列表最前面，而排序时间应该只由"发起对话"刷新。</p>
     */
    public AgentConversation markRead(Long conversationId, Long userId) {
        requireOwned(conversationId, userId);
        conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                .eq(AgentConversation::getId, conversationId)
                .eq(AgentConversation::getUserId, userId)
                .set(AgentConversation::getLastReadAt, LocalDateTime.now())
                .setSql("updated_at = updated_at"));
        return withUnreadFlag(requireOwned(conversationId, userId));
    }

    /** 置顶/取消置顶；同样保留 updated_at，置顶只改分组不改组内时间排序。 */
    public AgentConversation setPinned(Long conversationId, Long userId, boolean pinned) {
        requireOwned(conversationId, userId);
        conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                .eq(AgentConversation::getId, conversationId)
                .eq(AgentConversation::getUserId, userId)
                .set(AgentConversation::getIsPinned, pinned ? 1 : 0)
                .setSql("updated_at = updated_at"));
        return withUnreadFlag(requireOwned(conversationId, userId));
    }

    /** 生成完成时间晚于最近阅读时间即为未读，对应列表上的蓝点提醒。 */
    private AgentConversation withUnreadFlag(AgentConversation conversation) {
        if (conversation == null) return null;
        LocalDateTime completedAt = conversation.getLastCompletedAt();
        LocalDateTime readAt = conversation.getLastReadAt();
        conversation.setUnread(completedAt != null && (readAt == null || readAt.isBefore(completedAt)));
        return conversation;
    }

    public List<AgentMessage> listMessages(Long conversationId, Long userId) {
        requireOwned(conversationId, userId);
        return hydrateAttachments(messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getConversationId, conversationId)
                .orderByAsc(AgentMessage::getId)));
    }

    /** 会话列表：置顶在前，组内按更新时间倒序；顺带收口中断会话，避免列表上一直转圈。 */
    public List<AgentConversation> listSessions(Long userId) {
        List<AgentConversation> sessions = conversationMapper.selectList(
                new LambdaQueryWrapper<AgentConversation>()
                        .eq(AgentConversation::getUserId, userId)
                        .orderByDesc(AgentConversation::getIsPinned)
                        .orderByDesc(AgentConversation::getUpdatedAt)
                        .last("LIMIT 50"));
        // resolveStale 对未超时的 running 与非 running 会话直接返回，正常情况下不产生写操作。
        return sessions.stream()
                .map(session -> withUnreadFlag(resolveStale(session, userId)))
                .toList();
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
                );
        return token;
    }

    /** 禁用分享：将 share_token 置 NULL。 */
    public void disableShare(Long conversationId, Long userId) {
        requireOwned(conversationId, userId);
        conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                .eq(AgentConversation::getId, conversationId)
                .eq(AgentConversation::getUserId, userId)
                .set(AgentConversation::getShareToken, null)
                );
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
        messageMapper.insert(message);
    }

    private void saveActiveMessage(Long conversationId, Long userId, long runVersion,
                                   String role, String kind, String toolName, String content) {
        eventProjector.saveActiveMessage(conversationId, userId, runVersion, role, kind, toolName, content);
    }

    /** One user turn owns one plan card; subsequent plan_tasks calls update that card's contents. */
    private void saveOrUpdateActivePlanMessage(Long conversationId, Long userId, long runVersion,
                                               String content) {
        eventProjector.saveOrUpdateActivePlanMessage(conversationId, userId, runVersion, content);
    }

    private AgentEvent appendActive(Long conversationId, Long userId, long runVersion,
                                    String eventType, Object data, Consumer<AgentEvent> listener) {
        return eventProjector.appendActive(conversationId, userId, runVersion, eventType, data, listener);
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

    private String describeTool(String toolName) {
        return StringUtils.hasText(toolName) ? toolName : "（空工具名）";
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
            return runLifecycle.isCancellationRequested(conversationId, userId, runVersion);
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
        private final boolean deliverDeltas;
        private final StringBuilder deltaBuffer = new StringBuilder();
        private boolean firstDeltaDelivered;

        private AnswerProgress(Long conversationId, Long userId, long runVersion,
                                Consumer<AgentEvent> listener, int step,
                                long turnStartedNanos, long modelStartedNanos, boolean deliverDeltas) {
            this.conversationId = conversationId;
            this.userId = userId;
            this.runVersion = runVersion;
            this.listener = listener;
            this.step = step;
            this.turnStartedNanos = turnStartedNanos;
            this.modelStartedNanos = modelStartedNanos;
            this.deliverDeltas = deliverDeltas;
        }

        private void accept(String delta) {
            if (delta == null || delta.isEmpty()) return;
            if (!deliverDeltas) return;
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
