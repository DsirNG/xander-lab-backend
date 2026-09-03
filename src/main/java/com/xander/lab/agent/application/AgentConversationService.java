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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
public class AgentConversationService {
    private static final int TOOL_DELTA_FLUSH_CHARS = 256;
    private static final int ANSWER_DELTA_FLUSH_CHARS = 24;
    private static final long MAX_LEASE_RENEW_INTERVAL_SECONDS = 60L;
    private static final String DEFAULT_TITLE = "新对话";
    /** “说交付了代码却什么都没给”的批评意见。做成常量，方便去重后只提一次。 */
    private static final String UNDELIVERED_CODE_CRITIQUE =
            "自检未通过：用户要的是可运行的代码，但你的回复里既没有代码围栏，也没有调用 emit_artifact，"
                    + "用户其实什么都没拿到。下一步必须调用 emit_artifact，把每个文件的完整源码交出来；"
                    + "确实做不到就如实说明卡在哪里，不要把没交付说成已完成。";

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
    private final AgentQuizService quizService;
    private final AgentExecutionJournal executionJournal;
    private final AgentRequestAssembler requestAssembler;
    private final AgentStepExecutor stepExecutor;

    @Autowired
    public AgentConversationService(AgentConversationMapper conversationMapper, AgentMessageMapper messageMapper,
                                    AgentModelClient modelClient, AgentToolRegistry toolRegistry,
                                    AgentConversationProperties properties, TransactionTemplate transactionTemplate,
                                    AgentEventService eventService, ScheduledExecutorService agentLeaseRenewer,
                                    BillingService billingService, ObjectMapper objectMapper,
                                    AgentPlanService planService, AgentMemoryService memoryService,
                                    AgentTurnEvaluator turnEvaluator, AgentQuizService quizService,
                                    AgentExecutionJournal executionJournal,
                                    AgentRequestAssembler requestAssembler, AgentStepExecutor stepExecutor) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.eventService = eventService;
        this.agentLeaseRenewer = agentLeaseRenewer;
        this.billingService = billingService;
        this.objectMapper = objectMapper;
        this.planService = planService;
        this.memoryService = memoryService;
        this.turnEvaluator = turnEvaluator;
        this.quizService = quizService;
        this.executionJournal = executionJournal;
        this.requestAssembler = requestAssembler;
        this.stepExecutor = stepExecutor;
    }

    /** Compatibility constructor retained for focused loop tests and embedded callers. */
    public AgentConversationService(AgentConversationMapper conversationMapper, AgentMessageMapper messageMapper,
                                    AgentModelClient modelClient, AgentToolRegistry toolRegistry,
                                    AgentConversationProperties properties, TransactionTemplate transactionTemplate,
                                    AgentEventService eventService, ScheduledExecutorService agentLeaseRenewer,
                                    BillingService billingService, ObjectMapper objectMapper,
                                    AgentPlanService planService, AgentMemoryService memoryService,
                                    AgentTurnEvaluator turnEvaluator, AgentQuizService quizService) {
        this(conversationMapper, messageMapper, modelClient, toolRegistry, properties, transactionTemplate,
                eventService, agentLeaseRenewer, billingService, objectMapper, planService, memoryService,
                turnEvaluator, quizService, AgentExecutionJournal.disabled(),
                AgentRequestAssembler.legacy(toolRegistry, properties, planService, quizService, objectMapper),
                AgentStepExecutor.legacy(modelClient));
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
            long runVersion = claimConversation(conversationId, userId);
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

    /**
     * Runs a claimed turn，{@code deepThinking} 决定是否允许"追求完整"类自检多花轮次。
     *
     * <p>默认关闭：计划自检会把已经写好的回复退回去重做一遍，用户那边表现为"答案出现又被撤回、
     * 而且很慢"。想要更细致的结果时由前端显式打开，把这份等待变成用户自己的选择。
     * 与诚实性无关的优化才受此开关约束，工具失败没交代仍然一律拦截。</p>
     */
    public AgentConversation executeClaimedTurn(Long conversationId, Long userId, long runVersion,
                                                String currentUserMessage, boolean deepThinking,
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
            return runLoop(conversationId, userId, runVersion, currentUserMessage, deepThinking, onEvent);
        } catch (AgentRunSupersededException exception) {
            log.info("agent worker generation superseded for conversation {} and run {}",
                    conversationId, runVersion);
            return requireOwned(conversationId, userId);
        } catch (RuntimeException exception) {
            // 这里最终会把未知异常转换成“智能体任务失败，请稍后重试”；必须在转换前记录完整堆栈，
            // 否则线上只能看到兜底文案，无法判断是模型、工具、数据库还是事件持久化失败。
            log.error("agent turn execution failed conversationId={} userId={} runVersion={} phase=runLoop exceptionType={} message={}",
                    conversationId, userId, runVersion, exception.getClass().getName(), exception.getMessage(), exception);
            AgentEvent errorEvent = null;
            try {
                errorEvent = markFailed(conversationId, userId, runVersion, safeMessage(exception));
            } catch (AgentRunSupersededException superseded) {
                log.info("agent failure ignored for superseded conversation {} and run {}",
                        conversationId, runVersion);
                return requireOwned(conversationId, userId);
            } catch (RuntimeException persistenceFailure) {
                exception.addSuppressed(persistenceFailure);
                log.error("agent terminal failure could not be persisted conversationId={} userId={} runVersion={} phase=markFailed exceptionType={} message={}",
                        conversationId, userId, runVersion, persistenceFailure.getClass().getName(),
                        persistenceFailure.getMessage(), persistenceFailure);
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
        int maxReflections = Math.max(0, properties.getMaxReflections());
        int reflections = 0;
        int failures = 0;
        JsonNode lastError = null;
        boolean quizCardEmitted = false;
        // 本轮有没有真的交付过代码：收口时用来判断“说完成了却什么都没给”。
        boolean artifactEmitted = false;
        // 上一步自检的批评意见，只注入紧随其后的那一步。
        String critique = null;
        // 已经提过的批评意见：同一句话第二次出现说明智能体不会因为再听一遍而改变做法，
        // 继续驳回只是让用户多等一轮、多看一张自检卡，所以直接放行。
        Set<String> spentCritiques = new HashSet<>();
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
            List<AgentMessage> messages = listMessages(conversationId, userId);
            AgentStepRequest request = requestAssembler.assemble(new AgentRequestAssemblyInput(
                    conversationId, userId, runVersion, step + 1, messages, plan, memoryContext,
                    lastError, critique, allowedTools, maxSteps - step, deepThinking));
            critique = null;
            long modelStartedNanos = System.nanoTime();
            // 会被自检驳回的草稿干脆不要流给用户：先出现再撤回，比慢一点难受得多。
            // 只在"这一步真有可能被驳回"时才憋着——计划自检要开了深度思考才会触发，
            // 上一次工具失败则不论模式都可能拦，所以两者分别判断。
            boolean planGateArmed = deepThinking && plan.stream().anyMatch(item -> !item.settled());
            boolean rejectionLikely = reflections < maxReflections && (planGateArmed || lastError != null);
            AnswerProgress answerProgress = new AnswerProgress(
                    conversationId, userId, runVersion, listener,
                    step + 1, turnStartedNanos, modelStartedNanos,
                    (!isQuizRequest(latestUserMessage) || quizCardEmitted) && !rejectionLikely);
            JsonNode decision;
            AgentStep journalStep = executionJournal.startStep(request);
            try {
                ModelCallResult call = stepExecutor.execute(journalStep, request, answerProgress::accept,
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
            } catch (RuntimeException exception) {
                throw exception;
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
                if (isQuizRequest(latestUserMessage) && allowedTools.contains(EmitQuizTool.NAME) && !quizCardEmitted
                        && reflections < maxReflections) {
                    reflections++;
                    critique = "自检未通过：用户要求出题，但你还没有调用 emit_quiz 下发答题卡。"
                            + "不要在 reply 里列题或选项；下一步必须调用 emit_quiz，普通练习可不传 quizSessionId。";
                    saveActiveMessage(conversationId, userId, runVersion,
                            "assistant", "reflection", null, critique);
                    appendActive(conversationId, userId, runVersion, "reflection",
                            JsonNodeFactory.instance.objectNode().put("round", reflections).put("critique", critique),
                            listener);
                    continue;
                }
                // 说做完了却什么都没给：用户要的是代码，回复里既没有代码围栏，这一轮也没发过交付卡。
                // 这是诚实性自检，和深度思考无关；同一句批评只提一次，提过就放行，不跟模型拉锯。
                if (reflections < maxReflections && allowedTools.contains(EmitArtifactTool.NAME)
                        && !spentCritiques.contains(UNDELIVERED_CODE_CRITIQUE)
                        && claimsUndeliveredCode(latestUserMessage, reply, artifactEmitted)) {
                    reflections++;
                    critique = UNDELIVERED_CODE_CRITIQUE;
                    spentCritiques.add(critique);
                    log.info("agent turn reflection conversation={} run={} round={} kind=UNDELIVERED_ARTIFACT",
                            conversationId, runVersion, reflections);
                    saveActiveMessage(conversationId, userId, runVersion,
                            "assistant", "reflection", null, critique);
                    appendActive(conversationId, userId, runVersion, "reflection",
                            JsonNodeFactory.instance.objectNode().put("round", reflections).put("critique", critique),
                            listener);
                    continue;
                }
                Optional<AgentTurnEvaluator.Critique> verdict =
                        turnEvaluator.critique(reply, plan, lastError);
                if (verdict.isPresent() && shouldReflect(verdict.get(), deepThinking,
                        reflections < maxReflections, spentCritiques)) {
                    // 自检不通过：不把这次回复交给用户，把批评意见回灌让智能体自己纠偏。
                    reflections++;
                    critique = verdict.get().text();
                    spentCritiques.add(critique);
                    log.info("agent turn reflection conversation={} run={} round={} kind={}",
                            conversationId, runVersion, reflections, verdict.get().kind());
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
                    quizCardEmitted = true;
                }
                // 交付卡同理：代码是这一轮真正的产出，必须落成消息，刷新页面后还能拿到源码。
                if (EmitArtifactTool.NAME.equals(toolName) && result.has(EmitArtifactTool.ARTIFACT_FIELD)) {
                    JsonNode artifact = result.path(EmitArtifactTool.ARTIFACT_FIELD);
                    saveActiveMessage(conversationId, userId, runVersion,
                            "assistant", "artifact", null, artifact.toString());
                    appendActive(conversationId, userId, runVersion, "artifact", artifact, listener);
                    artifactEmitted = true;
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

    /**
     * 这条自检结论值不值得再花一轮。
     *
     * <p>三条否决理由，都是从"用户体验"而不是"模型正确性"出发的：预算用光了不再纠缠；
     * 同一句批评已经提过一次，再提一次也只会得到同样的回复；纯粹追求完整的优化在
     * 非深度思考模式下让位于速度——用户要的是快点看到答案，而不是等一个更完美的答案。</p>
     */
    private boolean shouldReflect(AgentTurnEvaluator.Critique verdict, boolean deepThinking,
                                  boolean budgetLeft, Set<String> spentCritiques) {
        if (!budgetLeft) return false;
        if (spentCritiques.contains(verdict.text())) return false;
        return deepThinking || !verdict.optionalRefinement();
    }

    private boolean isQuizRequest(String message) {
        if (!StringUtils.hasText(message)) return false;
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("出题") || normalized.contains("题目") || normalized.contains("测验")
                || normalized.contains("练习") || normalized.contains("选择题") || normalized.contains("quiz")
                || normalized.contains("question");
    }

    /**
     * 用户要代码，却什么都没拿到。
     *
     * <p>三个条件同时成立才算：这一轮没发过交付卡、回复里也没有代码围栏、而回复还自称做完了。
     * 少任何一个都不拦——已经贴了围栏的照常放行，只是分析或提问的回复也不该被拦。</p>
     */
    private boolean claimsUndeliveredCode(String userMessage, String reply, boolean artifactEmitted) {
        if (artifactEmitted || !StringUtils.hasText(reply) || reply.contains("```")) return false;
        return isCodeRequest(userMessage) && claimsCompletion(reply);
    }

    /**
     * 这条诉求要的是可运行的代码吗。
     *
     * <p>要求“动手做”的动词和“代码产物”的名词同时出现，且不是写文章类的诉求。
     * 判宽了会让正常回复白等一轮自检，那正是用户抱怨过的体验，所以这里宁可漏判：
     * 真正的兜底靠 emit_artifact 的工具说明，这道门只拦最典型的“空口交付”。</p>
     */
    private boolean isCodeRequest(String message) {
        if (!StringUtils.hasText(message)) return false;
        String normalized = message.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "博客", "文章", "文案", "大纲", "总结", "报告", "邮件", "提醒")) return false;
        boolean build = containsAny(normalized, "写", "实现", "生成", "做一个", "做个", "搭", "开发", "编写",
                "create", "build", "write", "implement");
        boolean artifact = containsAny(normalized, "代码", "源码", "脚本", "示例", "例子", "程序", "项目",
                "组件", "页面", "网页", "接口", "服务端", "客户端", "demo", "html", "css", "javascript",
                "python", "code", "script", "component");
        return build && artifact;
    }

    /** 回复自称已经交付了。只看“做完了”这类断言，提问或给方案的回复不算。 */
    private boolean claimsCompletion(String reply) {
        String normalized = reply.toLowerCase(Locale.ROOT);
        return containsAny(normalized, "已完成", "已经完成", "完成了", "已实现", "已经实现", "已生成",
                "已经生成", "已写好", "写好了", "已创建", "已交付", "已为你", "已经为你", "搞定",
                "如下", "见下", "done");
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) return true;
        }
        return false;
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
                    // 终态时间是列表未读提醒的事实来源：用户不在这个会话时靠它点亮蓝点。
                    .set(AgentConversation::getLastCompletedAt, now)
                    );
            if (updated != 1) {
                throw new AgentRunSupersededException(conversationId, runVersion);
            }
            executionJournal.completeRun(conversationId, userId, runVersion);
            current.setStatus("ready");
            current.setErrorMessage(null);
            current.setCancelRequested(0);
            current.setLastCompletedAt(now);
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
                        ));
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
                        .setSql("updated_at = CURRENT_TIMESTAMP")
                        );
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
                );
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
            LocalDateTime now = LocalDateTime.now();
            int updated = conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                    .eq(AgentConversation::getId, conversationId)
                    .eq(AgentConversation::getUserId, userId)
                    .eq(AgentConversation::getStatus, "running")
                    .eq(AgentConversation::getRunVersion, runVersion)
                    .set(AgentConversation::getStatus, "failed")
                    .set(AgentConversation::getErrorMessage, safeError)
                    // 失败同样是"这一轮有结果要看"，与成功收口一样点亮未读提醒。
                    .set(AgentConversation::getLastCompletedAt, now)
                    );
            if (updated != 1) {
                throw new AgentRunSupersededException(conversationId, runVersion);
            }
            executionJournal.failRun(conversationId, userId, runVersion, safeError);
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
        transactionTemplate.execute(status -> {
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
                    // 中断收口也是终态：列表要能从"转圈"切到"有结果待看"，否则会一直转圈。
                    .set(AgentConversation::getLastCompletedAt, now)
                    );
            long runVersion = conversation.getRunVersion() == null ? 0L : conversation.getRunVersion();
            if (changed == 1) {
                executionJournal.failRun(conversation.getId(), userId, runVersion, interrupted);
            }
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
        AgentMessage message = new AgentMessage();
        message.setConversationId(conversationId);
        message.setRunVersion(runVersion);
        message.setRole(role);
        message.setKind(kind);
        message.setToolName(toolName);
        message.setContent(content);
        transactionTemplate.executeWithoutResult(status -> {
            int touched = conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                    .eq(AgentConversation::getId, conversationId)
                    .eq(AgentConversation::getUserId, userId)
                    .eq(AgentConversation::getStatus, "running")
                    .eq(AgentConversation::getRunVersion, runVersion)
                    // MyBatis-Plus update wrapper 必须包含 SET；同时用更新时间续租当前执行中的会话。
                    .setSql("updated_at = CURRENT_TIMESTAMP")
                    );
            if (touched != 1 || messageMapper.insertForActiveRun(message, userId, runVersion) != 1) {
                throw new AgentRunSupersededException(conversationId, runVersion);
            }
        });
    }

    /** One user turn owns one plan card; subsequent plan_tasks calls update that card's contents. */
    private void saveOrUpdateActivePlanMessage(Long conversationId, Long userId, long runVersion,
                                               String content) {
        transactionTemplate.executeWithoutResult(status -> {
            int touched = conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                    .eq(AgentConversation::getId, conversationId)
                    .eq(AgentConversation::getUserId, userId)
                    .eq(AgentConversation::getStatus, "running")
                    .eq(AgentConversation::getRunVersion, runVersion)
                    .setSql("updated_at = CURRENT_TIMESTAMP"));
            if (touched != 1) throw new AgentRunSupersededException(conversationId, runVersion);

            int updated = messageMapper.update(null, new LambdaUpdateWrapper<AgentMessage>()
                    .eq(AgentMessage::getConversationId, conversationId)
                    .eq(AgentMessage::getRunVersion, runVersion)
                    .eq(AgentMessage::getKind, "plan")
                    .set(AgentMessage::getContent, content));
            if (updated > 0) return;

            AgentMessage message = new AgentMessage();
            message.setConversationId(conversationId);
            message.setRunVersion(runVersion);
            message.setRole("assistant");
            message.setKind("plan");
            message.setContent(content);
            if (messageMapper.insertForActiveRun(message, userId, runVersion) != 1) {
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
