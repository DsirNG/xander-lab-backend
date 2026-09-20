package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.agent.application.AgentTool.ProgressListener;
import com.xander.lab.agent.domain.AgentConversation;
import com.xander.lab.agent.domain.AgentApproval;
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
import com.xander.lab.payment.api.BillingBiz;
import com.xander.lab.payment.api.BillingPort;
import com.xander.lab.payment.api.TokenUsage;
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
import java.util.ArrayList;
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
    /**
     * 流式回答的合并时间窗（毫秒）。约束的是"用户多久看到下一段文字"，
     * 也同时决定了数据库写入频率；100ms 约等于长文阅读能感知顺滑的帧率，
     * 同时把 token 级事件量压到十分之一以下。
     */
    private static final long ANSWER_DELTA_BROADCAST_MILLIS = 100;
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
    private final BillingPort billingPort;
    private final ObjectMapper objectMapper;
    private final AgentPlanService planService;
    private final AgentMemoryService memoryService;
    private final AgentQuizService quizService;
    private final AgentExecutionJournal executionJournal;
    private final AgentRequestAssembler requestAssembler;
    private final AgentRunLifecycle runLifecycle;
    private final AgentEventProjector eventProjector;
    private final AgentTurnCoordinator turnCoordinator;
    private final AgentSkillCatalog skillCatalog;
    private final AgentCompactionService compactionService;

    @Autowired
    public AgentConversationService(AgentConversationMapper conversationMapper, AgentMessageMapper messageMapper,
                                    AgentModelClient modelClient, AgentToolRegistry toolRegistry,
                                    AgentConversationProperties properties, TransactionTemplate transactionTemplate,
                                    AgentEventService eventService,
                                    BillingPort billingPort, ObjectMapper objectMapper,
                                    AgentPlanService planService, AgentMemoryService memoryService,
                                    AgentQuizService quizService,
                                     AgentExecutionJournal executionJournal,
                                     AgentRequestAssembler requestAssembler,
                                     AgentRunLifecycle runLifecycle, AgentEventProjector eventProjector,
                                     AgentTurnCoordinator turnCoordinator, AgentSkillCatalog skillCatalog,
                                     AgentCompactionService compactionService) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.eventService = eventService;
        this.billingPort = billingPort;
        this.objectMapper = objectMapper;
        this.planService = planService;
        this.memoryService = memoryService;
        this.quizService = quizService;
        this.executionJournal = executionJournal;
        this.requestAssembler = requestAssembler;
        this.runLifecycle = runLifecycle;
        this.eventProjector = eventProjector;
        this.turnCoordinator = turnCoordinator;
        this.skillCatalog = skillCatalog;
        this.compactionService = compactionService;
    }

    /** Compatibility constructor retained for focused loop tests and embedded callers. */
    public AgentConversationService(AgentConversationMapper conversationMapper, AgentMessageMapper messageMapper,
                                    AgentModelClient modelClient, AgentToolRegistry toolRegistry,
                                    AgentConversationProperties properties, TransactionTemplate transactionTemplate,
                                    AgentEventService eventService, ScheduledExecutorService agentLeaseRenewer,
                                    BillingPort billingPort, ObjectMapper objectMapper,
                                    AgentPlanService planService, AgentMemoryService memoryService,
                                    AgentQuizService quizService) {
        this(conversationMapper, messageMapper, modelClient, toolRegistry, properties, transactionTemplate,
                eventService, billingPort, objectMapper, planService, memoryService,
                quizService, AgentExecutionJournal.disabled(),
                AgentRequestAssembler.legacy(toolRegistry, properties, planService, quizService, objectMapper),
                 new AgentRunLifecycle(conversationMapper, eventService, AgentExecutionJournal.disabled(),
                         properties, transactionTemplate, agentLeaseRenewer),
                 new AgentEventProjector(conversationMapper, messageMapper, eventService, transactionTemplate),
                 new AgentTurnCoordinator(conversationMapper, eventService,
                         new AgentRunLifecycle(conversationMapper, eventService, AgentExecutionJournal.disabled(),
                                 properties, transactionTemplate, agentLeaseRenewer),
                         AgentExecutionJournal.disabled(), AgentStepExecutor.legacy(modelClient),
                         AgentToolExecutionPipeline.legacy(toolRegistry)),
                 AgentSkillCatalog.empty(),
                 new AgentCompactionService(modelClient, properties,
                         new AgentEventProjector(conversationMapper, messageMapper, eventService,
                                 transactionTemplate)));
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
        billingPort.ensureAvailable(userId);
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

    /** Executes one already-approved invocation, then resumes the parent run from its next model step. */
    void resumeApprovedInvocation(AgentApproval approval, AgentToolInvocation invocation) {
        Long conversationId = approval.getConversationId();
        Long userId = approval.getUserId();
        long runVersion = requireRunVersion(invocation, approval);
        AgentTool tool = toolRegistry.get(invocation.getToolName());
        if (tool == null) {
            failApprovedInvocation(approval, invocation, "审批关联的工具不存在", false);
            return;
        }
        UsageAccumulator approvalUsage = new UsageAccumulator();
        ToolProgress progress = new ToolProgress(conversationId, userId, runVersion, invocation.getToolName(),
                invocation.getInvocationId(), ignored -> { }, approvalUsage);
        try {
            JsonNode arguments = objectMapper.readTree(invocation.getArgumentsJson());
            progress.checkCancellation();
            JsonNode result = tool.execute(new AgentToolContext(userId, conversationId, runVersion,
                    invocation.getInvocationId()), arguments, progress);
            progress.flushToolDelta();
            executionJournal.completeInvocation(invocation, result);
            saveActiveToolMessage(conversationId, userId, runVersion, "tool_result", invocation.getToolName(),
                    invocation.getInvocationId(), result.toString());
            appendActive(conversationId, userId, runVersion, "tool_end",
                    JsonNodeFactory.instance.objectNode()
                            .put("tool", invocation.getToolName())
                            .put("invocationId", invocation.getInvocationId())
                            .set("result", result),
                    ignored -> { });
            appendActive(conversationId, userId, runVersion, "approval_executed",
                    java.util.Map.of("approvalId", approval.getId(), "invocationId", invocation.getInvocationId(),
                            "tool", invocation.getToolName()), ignored -> { });
            executeClaimedTurn(conversationId, userId, runVersion, null, false, ignored -> { });
        } catch (CancellationException exception) {
            progress.flushToolDelta();
            executionJournal.cancelInvocation(invocation);
            // Re-enter the normal loop so cancellation writes the usual user-visible answer and billing closure.
            executeClaimedTurn(conversationId, userId, runVersion, null, false, ignored -> { });
        } catch (Exception exception) {
            progress.flushToolDelta();
            failApprovedInvocation(approval, invocation, safeMessage(exception),
                    AgentToolOutcomeClassifier.requiresReconciliation(exception));
        }
    }

    /** Persists a terminal failure when a resumed approval cannot enter the bounded agent worker pool. */
    void failResumedApproval(AgentApproval approval, AgentToolInvocation invocation, String reason) {
        executionJournal.failInvocation(invocation, reason);
        AgentEvent errorEvent = runLifecycle.fail(approval.getConversationId(), approval.getUserId(),
                requireOwned(approval.getConversationId(), approval.getUserId()).getRunVersion(), reason);
        eventService.deliver(errorEvent, ignored -> { });
    }

    private long requireRunVersion(AgentToolInvocation invocation, AgentApproval approval) {
        if (invocation.getRunId() == null || !invocation.getRunId().equals(approval.getRunId())) {
            throw new IllegalStateException("审批调用与运行记录不一致");
        }
        AgentConversation conversation = requireOwned(approval.getConversationId(), approval.getUserId());
        if (!"running".equals(conversation.getStatus())) {
            throw new AgentRunSupersededException(approval.getConversationId(),
                    conversation.getRunVersion() == null ? 0L : conversation.getRunVersion());
        }
        return conversation.getRunVersion();
    }

    private void failApprovedInvocation(AgentApproval approval, AgentToolInvocation invocation, String reason,
                                        boolean outcomeUnknown) {
        if (outcomeUnknown) {
            executionJournal.markInvocationUnknown(invocation, "工具远端执行结果未确认：" + reason);
        } else {
            executionJournal.failInvocation(invocation, reason);
        }
        AgentEvent errorEvent = runLifecycle.fail(approval.getConversationId(), approval.getUserId(),
                requireOwned(approval.getConversationId(), approval.getUserId()).getRunVersion(), reason);
        eventService.deliver(errorEvent, ignored -> { });
    }

    private AgentConversation runLoop(Long conversationId, Long userId, long runVersion,
                                      String currentUserMessage, boolean deepThinking,
                                      Consumer<AgentEvent> listener) {
        long turnStartedNanos = System.nanoTime();
        // 答题卡提交是一段 JSON，拿它去召回记忆只会命中噪声；这种轮次退回上一条真实诉求。
        String latestUserMessage = StringUtils.hasText(currentUserMessage)
                && !quizService.isSubmission(currentUserMessage)
                ? currentUserMessage : latestUserMessage(conversationId, userId);
        // 工具表每轮固定为全表，技能只影响"怎么干"，不再决定"能干什么"。
        // 早先是关键词命中的技能整体替换工具表，于是关键词决定了模型有什么能力——一句"给我一个 sse 代码"
        // 当轮只剩 emit_artifact，除了开交付卡别无选择，而用户要的只是一段片段；
        // 反过来"帮我记住这段话"没命中词表，记忆工具就整轮不在表里，模型只能空口答应。
        // 带外部副作用的调用由审批策略拦（见 AgentToolExecutionPipeline），而不是靠"看不见就调不动"。
        Set<String> allowedTools = toolRegistry.allowedNames(userId);
        AgentSkillOffer skillOffer = skillCatalog.offer(userId, allowedTools);
        executionJournal.recordSkillOffer(conversationId, userId, runVersion, skillOffer);
        String memoryContext = recallMemoryContext(userId, latestUserMessage);
        // 工具面（schema + 工具准则 + 技能目录）整轮只算一次：它不依赖步序也不依赖对话记录，
        // 压缩调用与主调用必须拿到逐字节相同的那一份，摘要请求才是主请求的真前缀，
        // 否则上游前缀缓存整段失效。现算两次也能相同，但一次算好就不会被后来的改动改坏。
        String toolSurface = toolRegistry.describeToolSurface(allowedTools, skillOffer.catalog());
        log.info("agent turn started conversation={} run={} toolCount={} skillCatalog={} memoryInjected={}",
                conversationId, runVersion, allowedTools.size(), skillOffer.skills().size(),
                !memoryContext.isEmpty());
        int maxSteps = Math.max(1, properties.getMaxSteps());
        int nextStepNo = executionJournal.nextStepNo(conversationId, runVersion);
        int failures = 0;
        JsonNode lastError = null;
        // 会话行在一步之内不会被别人改写；只有本轮的 plan_tasks 工具会动它，
        // 所以缓存它、并用 planDirty 标记"计划刚被改写、下一步要重新读"，避免每步重复查库。
        com.xander.lab.agent.domain.AgentConversation ownedConversation = null;
        boolean planDirty = true;
        UsageAccumulator usage = new UsageAccumulator();
        executionJournal.usageBeforeStep(conversationId, runVersion, nextStepNo)
                .ifPresent(carried -> usage.addPersisted(carried.usage(), carried.model()));

        for (int step = nextStepNo; step <= maxSteps; step++) {
            // 用户取消请求优先于模型调用，工具执行结束后下一轮即退出。
            if (runLifecycle.isCancellationRequested(conversationId, userId, runVersion)) {
                String stopped = "好的，已停止当前任务。你可以继续说新的需求。";
                saveActiveMessage(conversationId, userId, runVersion, "assistant", "answer", null, stopped);
                appendActive(conversationId, userId, runVersion, "answer", stopped, listener);
                return finalizeReady(conversationId, userId, runVersion, usage, listener);
            }
            // 每一步重新读计划：智能体上一步可能刚改写过它，这是会话状态而不是一次性输入。
            // 但只有会写计划的工具改动过它才需要重新查库——否则整个 runLoop 每步都在
            // 重复读取同一行会话（一次 selectById + 一次全量消息查询），步数越多越拖。
            if (planDirty || ownedConversation == null) {
                ownedConversation = requireOwned(conversationId, userId);
                planDirty = false;
            }
            List<AgentPlanItem> plan = planService.load(ownedConversation);
            JsonNode decision;
            AgentStep journalStep;
            try {
                // 压缩排在装配之前：装配吃掉的正是这一步可见的那段对话记录。
                // 它自己也烧 token，用量并回本轮账单，否则压缩就成了一条绕过计费的模型调用。
                List<AgentMessage> history = listMessages(conversationId, userId);
                AgentCompactionService.Compacted compacted = compactionService.compactIfNeeded(
                        conversationId, userId, runVersion, history,
                        toolSurface,
                        () -> runLifecycle.isCancellationRequested(conversationId, userId, runVersion));
                usage.add(compacted.usage(), compacted.model());
                // 未裁剪的整条会话一并交给装配层：压缩换掉的那一段里可能有模型花过一次调用读回来的
                // 技能正文，它得把那几条接回来，否则模型下一步照着的是一份被摘要过的规则。
                AgentStepRequest request = requestAssembler.assemble(new AgentRequestAssemblyInput(
                        conversationId, userId, runVersion, step, compacted.messages(), plan, memoryContext,
                        lastError, null, allowedTools, maxSteps - step + 1, deepThinking, skillOffer, history));
                long modelStartedNanos = System.nanoTime();
                AnswerProgress answerProgress = new AnswerProgress(
                        conversationId, userId, runVersion, listener,
                        step, turnStartedNanos, modelStartedNanos,
                        true);
                ExecutedModelStep executedStep = turnCoordinator.executeModelStep(request, answerProgress::accept,
                        () -> runLifecycle.isCancellationRequested(conversationId, userId, runVersion));
                ModelCallResult call = executedStep.result();
                journalStep = executedStep.step();
                answerProgress.flush();
                decision = call.payload();
                usage.add(call.usage(), call.model());
                log.info("agent model decision conversation={} run={} step={} durationMs={} done={}",
                        conversationId, runVersion, step,
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

            // thought 是实时推给用户的一行进度，不是模型的内部推理；
            // 提示词这么要求了，但保证在这里（见 AgentThoughtSanitizer）。
            String thought = AgentThoughtSanitizer.sanitize(
                    decision.path("thought").asText(""), toolRegistry.names());
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

            AgentActionPlan actionPlan = AgentActionPlan.from(
                    decision, allowedTools, properties.getMaxParallelTools());
            if (actionPlan.rejected()) {
                // 整批拒绝而不是执行一半：半批跑完、半批被拒，模型分不清哪一半生效，
                // 只会把已经成功的那部分重复调一遍。整批退回时它至少知道什么都没发生。
                failures++;
                lastError = JsonNodeFactory.instance.objectNode()
                        .put("ok", false)
                        .put("error", actionPlan.rejection())
                        .put("availableTools", String.join(", ", allowedTools));
                if (failures >= Math.max(1, properties.getMaxToolFailures())) {
                    String rejected = String.join("、",
                            actionPlan.rejectedTools().stream().map(this::describeTool).toList());
                    String reply = "很抱歉，我连续几次都没选对可用的工具（" + rejected
                            + " 不存在），这一轮先停在这里。请把需求说得更具体一些，或换个说法再试。";
                    saveActiveMessage(conversationId, userId, runVersion, "assistant", "answer", null, reply);
                    appendActive(conversationId, userId, runVersion, "answer", reply, listener);
                    return finalizeReady(conversationId, userId, runVersion, usage, listener);
                }
                continue;
            }

            List<AgentToolCall> calls = actionPlan.calls();
            List<ToolProgress> progresses = new ArrayList<>(calls.size());
            for (AgentToolCall call : calls) {
                progresses.add(new ToolProgress(conversationId, userId, runVersion, call.toolName(), listener, usage));
            }
            // 执行管道要的是接口列表，收口时又要调 ToolProgress 自己的 flushToolDelta，
            // 所以这里留两份视图而不是来回强转。
            List<ProgressListener> progressListeners = new ArrayList<>(progresses);
            try {
                List<ExecutedToolCall> executed = turnCoordinator.executeTools(
                        journalStep, userId, conversationId, runVersion, allowedTools, calls, progressListeners,
                        (call, invocationId) -> {
                            ObjectNode callNode = JsonNodeFactory.instance.objectNode();
                            callNode.put("tool", call.toolName());
                            callNode.set("args", call.args());
                            // 身份落在列上（tool_call 与 tool_result 都能读到），正文里不再留一份：
                            // 正文会被渲染进提示词（见 AgentRequestAssembler 的「（调用了工具）」），
                            // 多一个模型用不上的 UUID 是纯浪费；两份身份也迟早会漂移。
                            saveActiveToolMessage(conversationId, userId, runVersion, "tool_call",
                                    call.toolName(), invocationId, callNode.toString());
                            appendActive(conversationId, userId, runVersion, "tool_start",
                                    withInvocationId(callNode, invocationId), listener);
                        },
                        calls.size() > 1 && calls.stream().allMatch(call -> concurrencySafe(call, allowedTools)));

                Exception lastFailure = null;
                for (int index = 0; index < executed.size(); index++) {
                    ExecutedToolCall outcome = executed.get(index);
                    AgentToolCall call = outcome.call();
                    ToolProgress progress = progresses.get(index);
                    progress.flushToolDelta();
                    if (outcome.failed()) {
                        failures++;
                        lastFailure = outcome.failure();
                        ObjectNode errorNode = JsonNodeFactory.instance.objectNode();
                        errorNode.put("ok", false);
                        errorNode.put("tool", call.toolName());
                        errorNode.put("error", safeMessage(outcome.failure()));
                        saveActiveToolMessage(conversationId, userId, runVersion, "tool_result",
                                call.toolName(), outcome.invocationId(), errorNode.toString());
                        appendActive(conversationId, userId, runVersion, "tool_error",
                                withInvocationId(errorNode, outcome.invocationId()), listener);
                        lastError = errorNode;
                        continue;
                    }
                    JsonNode result = outcome.result();
                    saveActiveToolMessage(conversationId, userId, runVersion, "tool_result",
                            call.toolName(), outcome.invocationId(), result.toString());
                    appendActive(conversationId, userId, runVersion, "tool_end",
                            withInvocationId(JsonNodeFactory.instance.objectNode()
                                    .put("tool", call.toolName()).set("result", result), outcome.invocationId()),
                            listener);
                    planDirty = persistToolSideEffects(conversationId, userId, runVersion,
                            call.toolName(), result, listener) || planDirty;
                    failures = 0;
                    lastError = null;
                }
                // 被单步上限截掉的调用必须留下"未执行"的记录。静默丢弃的后果不是少一次调用，
                // 而是模型以为自己问过、只是没收到答案，于是拿着不完整的信息收口。
                // 这些调用从未开单，所以没有 invocationId——前端回退到按工具名配对，
                // 而它们本来也没有对应的 tool_call，会各自单独成卡。
                for (AgentToolCall skippedCall : actionPlan.skipped()) {
                    ObjectNode skippedNode = JsonNodeFactory.instance.objectNode();
                    skippedNode.put("ok", false);
                    skippedNode.put("tool", skippedCall.toolName());
                    skippedNode.put("error", "本步一次最多执行 " + Math.max(1, properties.getMaxParallelTools())
                            + " 个工具调用，该调用未执行，请在下一步重新发起");
                    saveActiveToolMessage(conversationId, userId, runVersion, "tool_result",
                            skippedCall.toolName(), null, skippedNode.toString());
                    appendActive(conversationId, userId, runVersion, "tool_error", skippedNode, listener);
                }
                if (failures >= Math.max(1, properties.getMaxToolFailures())) {
                    String reply = "很抱歉，连续多次尝试后仍然失败：" + safeMessage(lastFailure)
                            + "。请换个说法重试，或调整你的需求。";
                    saveActiveMessage(conversationId, userId, runVersion, "assistant", "answer", null, reply);
                    appendActive(conversationId, userId, runVersion, "answer", reply, listener);
                    return finalizeReady(conversationId, userId, runVersion, usage, listener);
                }
            } catch (AgentApprovalRequiredException exception) {
                for (ToolProgress progress : progresses) {
                    progress.flushToolDelta();
                }
                AgentEvent approvalEvent = runLifecycle.waitForApproval(conversationId, userId, runVersion,
                        exception.approval());
                eventService.deliver(approvalEvent, listener);
                return requireOwned(conversationId, userId);
            } catch (CancellationException exception) {
                String stopped = "好的，已停止当前任务。你可以继续说新的需求。";
                for (int index = 0; index < calls.size(); index++) {
                    AgentToolCall call = calls.get(index);
                    ToolProgress progress = progresses.get(index);
                    progress.flushToolDelta();
                    // 身份从这里取而不是从结果对象取：executeBatch 抛异常时结果列表不会返回，
                    // 而监听器在开单那一刻就已经拿到了自己那次调用的身份。
                    String invocationId = progress.invocationId();
                    saveActiveToolMessage(conversationId, userId, runVersion, "tool_result",
                            call.toolName(), invocationId,
                            JsonNodeFactory.instance.objectNode()
                                    .put("tool", call.toolName())
                                    .put("cancelled", true)
                                    .put("error", "已停止")
                                    .toString());
                    appendActive(conversationId, userId, runVersion, "tool_error",
                            withInvocationId(JsonNodeFactory.instance.objectNode()
                                    .put("tool", call.toolName())
                                    .put("cancelled", true)
                                    .put("error", "已停止"), invocationId),
                            listener);
                }
                saveActiveMessage(conversationId, userId, runVersion, "assistant", "answer", null, stopped);
                appendActive(conversationId, userId, runVersion, "answer", stopped, listener);
                return finalizeReady(conversationId, userId, runVersion, usage, listener);
            } catch (Exception e) {
                log.error("agent tool execution failed conversationId={} userId={} runVersion={} phase=tool.execute tools={} exceptionType={} message={}",
                        conversationId, userId, runVersion,
                        calls.stream().map(AgentToolCall::toolName).toList(),
                        e.getClass().getName(), e.getMessage(), e);
                // 走到这里说明是批次级的失败（整批校验没过、或执行池本身出问题），
                // 不是某个工具自己报错——那种情况在 executeBatch 里已经被收敛成结果了。
                // 但每个调用都已经落过 tool_call，所以逐个补一条结果，界面上不留悬空的调用。
                ObjectNode errorNode = null;
                for (int index = 0; index < calls.size(); index++) {
                    AgentToolCall call = calls.get(index);
                    ToolProgress progress = progresses.get(index);
                    progress.flushToolDelta();
                    String invocationId = progress.invocationId();
                    errorNode = JsonNodeFactory.instance.objectNode();
                    errorNode.put("ok", false);
                    errorNode.put("tool", call.toolName());
                    errorNode.put("error", safeMessage(e));
                    saveActiveToolMessage(conversationId, userId, runVersion, "tool_result",
                            call.toolName(), invocationId, errorNode.toString());
                    appendActive(conversationId, userId, runVersion, "tool_error",
                            withInvocationId(errorNode, invocationId), listener);
                }
                failures++;
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

    private JsonNode progressPayload(String tool, String invocationId, String message) {
        return eventProjector.progressPayload(tool, invocationId, message);
    }

    /**
     * 给工具事件的载荷补上 {@code invocationId}，**返回副本、不改原对象**。
     *
     * <p>为什么必须是副本：失败分支里的同一个 {@code errorNode} 还有别的去处——它会被存成
     * {@code lastError} 并渲染进下一轮提示词。就地加上身份，模型就会收到一个 UUID 观察值，
     * 既白烧 token 又可能被它当成需要维护的字段。副本让"给人看的事件"和"给模型看的内容"
     * 各自干净。</p>
     *
     * <p>单独抽一个方法是为了让补身份这件事在所有事件出口上长得一样。漏掉一处不会报错，
     * 只会让那一条事件回退到按工具名配对，而回退在同名工具上恰好是错的——这种只错一部分的
     * 漏洞最难发现。</p>
     */
    private static ObjectNode withInvocationId(ObjectNode payload, String invocationId) {
        ObjectNode copy = payload.deepCopy();
        if (invocationId != null) {
            copy.put("invocationId", invocationId);
        }
        return copy;
    }

    private AgentConversation finalizeReady(Long conversationId, Long userId, long runVersion,
                                            UsageAccumulator usage, Consumer<AgentEvent> onEvent) {
        TerminalTransition terminal = runLifecycle.complete(conversationId, userId, runVersion);
        chargeTurnQuietly(conversationId, userId, runVersion, usage);
        eventService.deliver(terminal.event(), onEvent);
        return terminal.conversation();
    }

    /**
     * 这一轮的计费。
     *
     * <p>计费是"成功哨点"上的动作，不是成功的前提：模型已经答完、消息也已经落库，
     * 此时账务失败再去 {@code fail()} 会走进一条死路——{@link AgentRunLifecycle#complete}
     * 已经把状态推到 {@code ready}，{@code fail()} 只认 {@code running}，于是抛
     * {@link AgentRunSupersededException}，被 {@link AgentTurnCoordinator} 当成"被新一轮顶掉"
     * 静默吞掉：终态事件不投递、日志写着 superseded，真正的账务故障一点痕迹都没有。</p>
     *
     * <p>所以这里自己兜住异常并打成 error 日志：会话照常 ready，事件照常投递，
     * 丢掉的账在日志里看得见，不会被伪装成一次正常的并发取代。</p>
     */
    private void chargeTurnQuietly(Long conversationId, Long userId, long runVersion, UsageAccumulator usage) {
        try {
            billingPort.chargeTokens(userId, BillingBiz.AGENT_TURN,
                    "conv:" + conversationId + ":run:" + runVersion, "conv:" + conversationId,
                    usage.sum(), usage.model(), "智能体会话单轮执行");
        } catch (RuntimeException exception) {
            log.error("agent turn billing failed conversationId={} userId={} runVersion={} phase=billing"
                            + " exceptionType={} message={}",
                    conversationId, userId, runVersion, exception.getClass().getName(),
                    exception.getMessage(), exception);
        }
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

    /**
     * 工具消息：除了工具名还要给出这次调用的持久化身份。
     *
     * <p>单开一个入口而不是给 {@link #saveActiveMessage} 加参数，是因为那个方法已经有七个
     * String 参数，再插一个同类型参数，调用点写反了编译器不会响。工具消息必须显式回答
     * "这是哪一次调用"，漏了是编译不过的。身份可为 null——从未开单的调用（被单步上限截掉的）
     * 本来就没有身份，前端会回退到按工具名配对。</p>
     */
    private void saveActiveToolMessage(Long conversationId, Long userId, long runVersion, String kind,
                                       String toolName, String invocationId, String content) {
        eventProjector.saveActiveToolMessage(conversationId, userId, runVersion, kind,
                toolName, invocationId, content);
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

    /**
     * 并发准入判定：只有**显式声明只读**、且这次调用不触发审批的工具，才允许与同批其它调用同时跑。
     *
     * <p>两条都是必要条件，缺一不可。只读解决"两个写操作同时改同一行"；
     * 无审批解决"审批是整轮级暂停，混在批次里会在半途挂起"。
     * {@code readOnly()} 默认 false，所以没声明过的工具一律退化成串行——慢一点，但不会写坏数据。</p>
     *
     * <p>{@code approvalReason} 在这里和 {@code AgentToolExecutionPipeline} 里各被调用一次。
     * 它按约定必须是纯函数（只看参数、无副作用），重复调用才是安全的。</p>
     */
    private boolean concurrencySafe(AgentToolCall call, Set<String> allowedTools) {
        if (!allowedTools.contains(call.toolName())) return false;
        AgentTool tool = toolRegistry.get(call.toolName());
        if (tool == null || !tool.readOnly()) return false;
        return tool.approvalReason(call.args()).isEmpty();
    }

    /**
     * 工具结果落库之后的会话级副作用。
     *
     * <p>这三种卡片各自要留一条**消息**而不只是一个事件：它们是用户接下来要操作的界面
     * （待办清单、答题卡、交付卡），刷新页面后必须还在，而事件只在本轮的流里活着。</p>
     *
     * @return 任务计划是否被改写。改写后下一步必须重新读会话行，
     *         否则上面注释里承诺的"每步重新读计划"就失效了
     */
    private boolean persistToolSideEffects(Long conversationId, Long userId, long runVersion,
                                          String toolName, JsonNode result, Consumer<AgentEvent> listener) {
        // 计划变更单独发一个事件：前端要能把它当成"智能体的待办清单"渲染，而不是一段工具输出。
        if (PlanTasksTool.NAME.equals(toolName) && result.has("items")) {
            saveOrUpdateActivePlanMessage(conversationId, userId, runVersion, result.path("items").toString());
            appendActive(conversationId, userId, runVersion, "plan",
                    JsonNodeFactory.instance.objectNode().set("items", result.path("items")), listener);
            return true;
        }
        // 答题卡要落成一条消息而不只是一个事件：它是用户接下来要操作的界面，
        // 刷新页面后必须还在，而事件只在本轮的流里活着。
        if (EmitQuizTool.NAME.equals(toolName) && result.has(EmitQuizTool.QUIZ_FIELD)) {
            JsonNode quiz = result.path(EmitQuizTool.QUIZ_FIELD);
            saveActiveMessage(conversationId, userId, runVersion, "assistant", "quiz", null, quiz.toString());
            appendActive(conversationId, userId, runVersion, "quiz", quiz, listener);
        }
        // 交付卡同理：代码是这一轮真正的产出，必须落成消息，刷新页面后还能拿到源码。
        if (EmitArtifactTool.NAME.equals(toolName) && result.has(EmitArtifactTool.ARTIFACT_FIELD)) {
            JsonNode artifact = result.path(EmitArtifactTool.ARTIFACT_FIELD);
            saveActiveMessage(conversationId, userId, runVersion, "assistant", "artifact", null, artifact.toString());
            appendActive(conversationId, userId, runVersion, "artifact", artifact, listener);
        }
        return false;
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
        /**
         * 这次调用的持久化身份。审批恢复那条路径在构造时就知道；编排循环里身份是执行管道
         * 开单时才生成的，所以先为 null，等 {@link #bindInvocationId} 补上。
         *
         * <p>写成可变字段而不是 final：唯一的赋值点（bindInvocationId）严格早于任何执行，
         * 之后的读取都在同一线程的 happens-before 边之内（管道开单 → 提交任务），不需要 volatile。</p>
         */
        private String invocationId;

        private ToolProgress(Long conversationId, Long userId, long runVersion,
                             String toolName, Consumer<AgentEvent> listener, UsageAccumulator usage) {
            this(conversationId, userId, runVersion, toolName, null, listener, usage);
        }

        private ToolProgress(Long conversationId, Long userId, long runVersion, String toolName,
                             String invocationId, Consumer<AgentEvent> listener, UsageAccumulator usage) {
            this.conversationId = conversationId;
            this.userId = userId;
            this.runVersion = runVersion;
            this.toolName = toolName;
            this.invocationId = invocationId;
            this.listener = listener;
            this.usage = usage;
        }

        @Override
        public void bindInvocationId(String invocationId) {
            this.invocationId = invocationId;
        }

        /**
         * 这次调用的身份，供收口路径（取消、批次级失败）在 {@code executeBatch} 已经抛出、
         * 拿不到结果对象时回填工具结果消息。从未开单时为 null。
         */
        private String invocationId() {
            return invocationId;
        }

        @Override
        public void onProgress(String message) {
            flushToolDelta();
            appendActive(conversationId, userId, runVersion, "tool_progress",
                    progressPayload(toolName, invocationId, message), listener);
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
            if (invocationId != null) {
                payload.put("invocationId", invocationId);
            }
            payload.put("delta", deltaBuffer.toString());
            deltaBuffer.setLength(0);
            appendActive(conversationId, userId, runVersion, "tool_delta", payload, listener);
        }
    }

    /**
     * 流式回复的推送策略：先立刻推第一个增量，之后按时间窗合并推送。
     *
     * <p>这里的核心是"按时间合并"而不是"按字符数合并"。早先是每攒够 24 个
     * 字符就同步落库一次并推送，于是</p>
     * <ul>
     *   <li>一次回答里几十次数据库往返被串行插在用户眼前，这是"响应很慢"的直接来源；</li>
     *   <li>而按字符合并对快模型形同虚设——上游一秒能吐几百字，24 字符阈值
     *       意味着仍然每几十毫秒写一次库，没有真正减少次数。</li>
     * </ul>
     *
     * <p>改成按 {@link #ANSWER_DELTA_BROADCAST_MILLIS} 的时间窗合并后，
     * 数据库写入次数只与"回答持续了多久"有关，与 token 密度无关；
     * 一次 10 秒的回答从上百次写库降到约 100 次以内，且用户看到的文字
     * 仍然以固定帧率平滑出现。第一个增量依旧立刻推送，保证首字延迟不受影响。</p>
     */
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
        /** 上次合并推送的时间点；用它把 token 级洪峰压成固定帧率。 */
        private long lastFlushNanos;
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
                // 第一个增量立刻推送：首字延迟由上游模型决定，不应再被一次数据库往返拖慢。
                firstDeltaDelivered = true;
                lastFlushNanos = System.nanoTime();
                eventProjector.appendLive(conversationId, userId, runVersion, "answer_delta",
                        normalized, listener);
                log.info("agent first visible delta conversation={} run={} step={} modelMs={} turnMs={}",
                        conversationId, runVersion, step,
                        elapsedMillis(modelStartedNanos), elapsedMillis(turnStartedNanos));
                return;
            }
            deltaBuffer.append(normalized);
            if (elapsedMillis(lastFlushNanos) >= ANSWER_DELTA_BROADCAST_MILLIS) flush();
        }

        /**
         * 窗口到期或整轮结束时，把缓冲区合并成一条事件推给用户。
         *
         * <p>走 {@code appendLive}（先推送、再落库）而不是 {@code appendActive}（先落库、再推送）：
         * 正文已经在内存里，没有理由让用户等一次数据库往返才看到它。</p>
         */
        private void flush() {
            if (deltaBuffer.isEmpty()) return;
            String payload = deltaBuffer.toString();
            deltaBuffer.setLength(0);
            lastFlushNanos = System.nanoTime();
            eventProjector.appendLive(conversationId, userId, runVersion, "answer_delta", payload, listener);
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

        /**
         * 并发批次里的工具可能从不同线程回报用量（子智能体这类工具会自己调模型），
         * 累加必须原子，否则 token 计数会丢更新、账单少算。
         */
        synchronized void add(ModelUsage usage, String usedModel) {
            if (usage == null) return;
            input += Math.max(0L, usage.inputTokens());
            cached += Math.max(0L, usage.cachedTokens());
            output += Math.max(0L, usage.outputTokens());
            if (StringUtils.hasText(usedModel)) {
                if (model == null) model = usedModel;
                else if (!model.equals(usedModel)) mixedModels = true;
            }
        }

        synchronized void addPersisted(ModelUsage usage, String usedModel) {
            add(usage, usedModel);
            if (usage != null && usedModel == null
                    && (usage.inputTokens() > 0 || usage.cachedTokens() > 0 || usage.outputTokens() > 0)) {
                mixedModels = true;
            }
        }

        /**
         * 计费契约口径的汇总。
         *
         * <p>累加用的是网关口径 {@code ModelUsage}，交出去的是计费口径 {@link TokenUsage}
         * —— 两者分量同名同序，所以这里是全类唯一一处换算，换算按<b>访问器</b>取、按位置放，
         * 不依赖任何一方的声明顺序。</p>
         */
        TokenUsage sum() {
            return new TokenUsage(input, cached, output);
        }

        String model() {
            return mixedModels ? null : model;
        }
    }
}
