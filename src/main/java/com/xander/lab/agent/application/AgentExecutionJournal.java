package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.xander.lab.agent.domain.AgentRun;
import com.xander.lab.agent.domain.AgentStep;
import com.xander.lab.agent.domain.AgentToolInvocation;
import com.xander.lab.agent.domain.RunBudget;
import com.xander.lab.agent.domain.RunUsage;
import com.xander.lab.agent.infrastructure.AgentConversationMapper;
import com.xander.lab.agent.infrastructure.AgentRunMapper;
import com.xander.lab.agent.infrastructure.AgentStepMapper;
import com.xander.lab.agent.infrastructure.AgentToolInvocationMapper;
import com.xander.lab.billing.application.BillingService;
import com.xander.lab.common.ModelCallResult;
import com.xander.lab.common.ModelUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes the execution facts around the existing conversation loop.
 *
 * <p>Phase 1 deliberately records minimal request metadata instead of raw prompts. This
 * preserves the current API while creating stable run, decision, and tool boundaries for
 * the later request assembler and tool pipeline.</p>
 */
@Slf4j
@Service
public class AgentExecutionJournal {
    private final AgentConversationMapper conversationMapper;
    private final AgentRunMapper runMapper;
    private final AgentStepMapper stepMapper;
    private final AgentToolInvocationMapper invocationMapper;
    private final BillingService billingService;
    /**
     * 逻辑副作用身份的生命周期。为 {@code null} 时本类退化成"不碰 Effect"——
     * 聚焦测试直接构造本类时不加载它，而"副作用身份"对它们本来也不是被测对象。
     */
    private final AgentEffectService effectService;
    private final boolean enabled;

    private AgentExecutionJournal() {
        this(null, null, null, null, null, null, false);
    }

    /** Compatibility instance for focused legacy loop tests that do not load persistence mappers. */
    static AgentExecutionJournal disabled() {
        return new AgentExecutionJournal();
    }

    @Autowired
    public AgentExecutionJournal(AgentConversationMapper conversationMapper, AgentRunMapper runMapper,
                                 AgentStepMapper stepMapper, AgentToolInvocationMapper invocationMapper,
                                 BillingService billingService, AgentEffectService effectService) {
        this(conversationMapper, runMapper, stepMapper, invocationMapper, billingService, effectService, true);
    }

    /** Compatibility constructor for focused journal tests that do not exercise effect identity. */
    public AgentExecutionJournal(AgentConversationMapper conversationMapper, AgentRunMapper runMapper,
                                 AgentStepMapper stepMapper, AgentToolInvocationMapper invocationMapper,
                                 BillingService billingService) {
        this(conversationMapper, runMapper, stepMapper, invocationMapper, billingService, null, true);
    }

    private AgentExecutionJournal(AgentConversationMapper conversationMapper, AgentRunMapper runMapper,
                                  AgentStepMapper stepMapper, AgentToolInvocationMapper invocationMapper,
                                  BillingService billingService, AgentEffectService effectService,
                                  boolean enabled) {
        this.conversationMapper = conversationMapper;
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.invocationMapper = invocationMapper;
        this.billingService = billingService;
        this.effectService = effectService;
        this.enabled = enabled;
    }

    /**
     * 创建 Run，并把这一轮的预算**冻结**下来。
     *
     * <p>预算在 claim 那一刻折算成快照写进 {@code budget_json}，之后整个 Run 只读它。
     * 理由见 {@link RunBudget}：运行期改配置不该让一个跑了一半的 Run 突然换判定标准。</p>
     */
    @Transactional
    public void startRun(Long conversationId, Long userId, long runVersion, RunBudget budget) {
        if (!enabled) return;
        AgentRun existing = findRun(conversationId, runVersion);
        if (existing != null) return;
        requireActiveConversation(conversationId, userId, runVersion);
        LocalDateTime now = LocalDateTime.now();
        AgentRun run = new AgentRun();
        run.setUserId(userId);
        run.setConversationId(conversationId);
        run.setRunVersion(runVersion);
        run.setStatus(AgentRun.STATUS_RUNNING);
        run.setStartedAt(now);
        // 新意图 = 新周期。这一步是幂等采纳的分界：resume 会复用这个值，于是"崩溃后模型重试
        // 同一个动作"落在同一周期里（可被识别为重试），而"用户又说了新的一句话"落在新周期里
        // （一定被当成新动作，不会被当成重试而吞掉）。
        run.setIntentEpoch(UUID.randomUUID().toString());
        run.setBudgetJson(RunBudgetJson.write(budget));
        // 进入 RUNNING 就开始计活跃时长。全系统只有两处"进入 RUNNING"：这里的 INSERT，
        // 以及 resumeRun 的 UPDATE。两处都必须写 last_running_at，漏一处那段活跃时间就永远不会被结算。
        run.setLastRunningAt(now);
        run.setActiveDurationMs(0L);
        if (runMapper.insert(run) != 1) {
            throw new IllegalStateException("智能体运行日志创建失败");
        }
    }

    /**
     * 读回本轮冻结的预算。
     *
     * <p>返回空表示这一行没有快照（本迁移之前创建的 Run，或快照损坏），调用方应退回当前配置。
     * **不要**在这里返回 {@code unlimited()}：那会把"快照缺失"伪装成"这个 Run 没有预算"。</p>
     */
    @Transactional(readOnly = true)
    public Optional<RunBudget> loadBudget(Long conversationId, long runVersion) {
        if (!enabled) return Optional.empty();
        AgentRun run = findRun(conversationId, runVersion);
        if (run == null) return Optional.empty();
        return Optional.ofNullable(RunBudgetJson.read(run.getBudgetJson()));
    }

    /**
     * 聚合这个 Run 到当前为止消耗的资源。
     *
     * <p>三个来源各自现算，不落 {@code used_*} 冗余列：步数与 token 来自 {@code agent_step}，
     * 调用条数来自 {@code agent_tool_invocation}，活跃时长来自 {@code agent_run}。
     * 两张明细表的索引都以 {@code run_id} 为前缀（{@code uk_agent_step_run_number}、
     * {@code idx_agent_tool_invocation_run_created}），所以这里是索引范围扫描而不是全表扫。</p>
     *
     * <p>活跃时长**包含正在进行的那一段**：{@code last_running_at} 非空时说明此刻正在 RUNNING，
     * 那一段还没被结算，但判定"会不会超时"必须把它算进去——否则一个已经跑了 29 分钟、
     * 上限 30 分钟的任务会在检查点被判为"才跑了 0 分钟"。</p>
     *
     * <p>返回的 {@code steps} 取库里 {@code max(step_no)}。**编排循环会用自己的计数器覆盖它**
     * （见 {@code AgentConversationService} 的检查点 1）：循环计数器才是"已经尝试了几步"的定义，
     * 而库里那一列在某一步没写全时会偏小，偏小意味着允许超出。这里仍然算出来是为了让
     * 日志与排障拿到一个库侧口径。</p>
     *
     * <p>毫分按**每个模型各自的单价**分别折算再求和，而不是拿聚合后的 token 乘一个单价：
     * 同一轮里压缩模型与主模型可能不同，混着算会得到一个既不是 A 也不是 B 的数字。</p>
     */
    @Transactional(readOnly = true)
    public RunUsage loadUsage(Long conversationId, long runVersion) {
        if (!enabled) return RunUsage.EMPTY;
        AgentRun run = findRun(conversationId, runVersion);
        if (run == null) return RunUsage.EMPTY;
        List<AgentStep> steps = stepMapper.selectList(new LambdaQueryWrapper<AgentStep>()
                .eq(AgentStep::getRunId, run.getId()));
        int usedSteps = 0;
        long input = 0L;
        long output = 0L;
        long cached = 0L;
        Map<String, long[]> tokensByModel = new LinkedHashMap<>();
        if (steps != null) {
            for (AgentStep step : steps) {
                if (step.getStepNo() != null && step.getStepNo() > usedSteps) {
                    usedSteps = step.getStepNo();
                }
                if (!AgentStep.STATUS_COMPLETED.equals(step.getStatus())) continue;
                long stepInput = Math.max(0L, valueOrZero(step.getInputTokens()));
                long stepOutput = Math.max(0L, valueOrZero(step.getOutputTokens()));
                long stepCached = Math.max(0L, valueOrZero(step.getCachedTokens()));
                input += stepInput;
                output += stepOutput;
                cached += stepCached;
                String model = step.getModelName() == null ? "" : step.getModelName();
                long[] bucket = tokensByModel.computeIfAbsent(model, ignored -> new long[3]);
                bucket[0] += stepInput;
                bucket[1] += stepCached;
                bucket[2] += stepOutput;
            }
        }
        Long toolCalls = invocationMapper.selectCount(new LambdaQueryWrapper<AgentToolInvocation>()
                .eq(AgentToolInvocation::getRunId, run.getId()));
        long milliPoints = 0L;
        for (Map.Entry<String, long[]> entry : tokensByModel.entrySet()) {
            long[] bucket = entry.getValue();
            milliPoints += billingService.estimateMilliPoints(
                    new ModelUsage(bucket[0], bucket[1], bucket[2]),
                    entry.getKey().isBlank() ? null : entry.getKey());
        }
        return new RunUsage(usedSteps, toolCalls == null ? 0L : toolCalls,
                input, output, cached, milliPoints, currentActiveDuration(run, LocalDateTime.now()));
    }

    /** 结算"正在进行的那一段"之后的累计活跃时长，供聚合与展示使用。 */
    private Duration currentActiveDuration(AgentRun run, LocalDateTime now) {
        long accumulated = run.getActiveDurationMs() == null ? 0L : Math.max(0L, run.getActiveDurationMs());
        LocalDateTime runningSince = run.getLastRunningAt();
        if (runningSince == null) return Duration.ofMillis(accumulated);
        return Duration.ofMillis(accumulated + elapsedMillisSince(runningSince, now));
    }

    /** 一段 RUNNING 的毫秒数，负数（时钟回拨）按 0 处理而不是倒扣。 */
    private static long elapsedMillisSince(LocalDateTime since, LocalDateTime until) {
        if (since == null || until == null) return 0L;
        return Math.max(0L, Duration.between(since, until).toSeconds()) * 1000L;
    }

    /**
     * 记下这一轮给模型的技能供给：目录里有什么、工具表是什么。
     *
     * <p>写的是供给而不是判定。早先这里落的是关键词路由的结果（命中了谁、置信度多少），
     * 而现在服务端不猜意图了——实际读了哪份技能只有模型知道，它体现为一条 {@code load_skill}
     * 工具调用，已经由步骤日志各自记下。这里能负责的事只剩"当时它能读到哪些"。</p>
     */
    @Transactional
    public void recordSkillOffer(Long conversationId, Long userId, long runVersion,
                                 AgentSkillOffer offer) {
        if (!enabled || offer == null) return;
        AgentRun run = findRun(conversationId, runVersion);
        if (run == null) throw new AgentRunSupersededException(conversationId, runVersion);
        int changed = runMapper.update(null, new LambdaUpdateWrapper<AgentRun>()
                .eq(AgentRun::getId, run.getId())
                .eq(AgentRun::getUserId, userId)
                .eq(AgentRun::getStatus, AgentRun.STATUS_RUNNING)
                .set(AgentRun::getIntentJson, offer.intentJson())
                .set(AgentRun::getSkillsJson, offer.skillsJson())
                .set(AgentRun::getExecutionSnapshotJson, offer.executionSnapshotJson()));
        if (changed != 1) throw new AgentRunSupersededException(conversationId, runVersion);
    }

    /** Writes a model-step intent before the remote model call starts. */
    @Transactional
    public AgentStep startStep(Long conversationId, Long userId, long runVersion, int stepNo,
                               String requestSnapshotJson) {
        if (!enabled) return disabledStep(stepNo);
        AgentRun run = requireRunningRun(conversationId, userId, runVersion);
        AgentStep step = new AgentStep();
        step.setRunId(run.getId());
        step.setStepNo(stepNo);
        step.setStatus(AgentStep.STATUS_RUNNING);
        step.setRequestSnapshotJson(requestSnapshotJson);
        step.setStartedAt(LocalDateTime.now());
        if (stepMapper.insert(step) != 1) {
            throw new IllegalStateException("智能体步骤日志创建失败");
        }
        return step;
    }

    /** Writes a fully assembled request's durable reference snapshot before the model call. */
    public AgentStep startStep(AgentStepRequest request) {
        return startStep(request.conversationId(), request.userId(), request.runVersion(), request.stepNo(),
                request.journalSnapshotJson());
    }

    /** Saves parsed model output and usage after a successful remote call. */
    @Transactional
    public void completeStep(AgentStep step, ModelCallResult call) {
        if (!enabled || step == null || step.getId() == null) return;
        ModelUsage usage = call.usage() == null ? ModelUsage.NONE : call.usage();
        int changed = stepMapper.update(null, new LambdaUpdateWrapper<AgentStep>()
                .eq(AgentStep::getId, step.getId())
                .eq(AgentStep::getStatus, AgentStep.STATUS_RUNNING)
                .set(AgentStep::getStatus, AgentStep.STATUS_COMPLETED)
                .set(AgentStep::getDecisionJson, call.payload() == null ? null : call.payload().toString())
                .set(AgentStep::getModelName, call.model())
                .set(AgentStep::getInputTokens, usage.inputTokens())
                .set(AgentStep::getCachedTokens, usage.cachedTokens())
                .set(AgentStep::getOutputTokens, usage.outputTokens())
                .set(AgentStep::getCompletedAt, LocalDateTime.now()));
        if (changed != 1) throw new IllegalStateException("智能体步骤日志状态已变化");
    }

    @Transactional
    public void failStep(AgentStep step, String reason) {
        transitionStep(step, AgentStep.STATUS_FAILED, reason);
    }

    @Transactional
    public void cancelStep(AgentStep step) {
        transitionStep(step, AgentStep.STATUS_CANCELLED, "用户取消");
    }

    /** Persists the invocation identity before any tool side effect can begin. */
    @Transactional
    public AgentToolInvocation startInvocation(AgentStep step, String invocationId, String toolName,
                                               JsonNode arguments) {
        if (!enabled) return disabledInvocation(invocationId, toolName);
        AgentToolInvocation invocation = new AgentToolInvocation();
        invocation.setRunId(step.getRunId());
        invocation.setStepId(step.getId());
        invocation.setInvocationId(invocationId);
        invocation.setToolName(toolName);
        invocation.setArgumentsJson(arguments == null ? null : arguments.toString());
        invocation.setStatus(AgentToolInvocation.STATUS_RUNNING);
        invocation.setStartedAt(LocalDateTime.now());
        if (invocationMapper.insert(invocation) != 1) {
            throw new IllegalStateException("智能体工具调用日志创建失败");
        }
        return invocation;
    }

    /**
     * 把这次调用接到它的逻辑副作用上，并把它置于 {@code PREPARED} 阶段。
     *
     * <p>落库的同时也写回内存对象：{@link #completeInvocation} 要用它去结算副作用，
     * 而那个方法拿到的就是同一个对象。只写库不写内存的话，成功路径会漏掉结算，
     * 于是一个**已经成功**的动作继续处于 OPEN，同周期里用户真的要做的第二次会被当成重试吞掉。</p>
     *
     * <p><b>为什么阶段标记写在这里。</b>{@code PREPARED} 的含义是"意图已落库、请求还没交给工具"，
     * 而"这次调用有副作用"这件事恰恰是在解析出 {@code effectId} 的那一刻才成立的——
     * {@link #startInvocation} 时还不知道。所以接上 Effect 与置为 PREPARED 是同一个事实的两面，
     * 分两处写迟早会有一处漏掉，而漏掉的表现是这次调用永远停在 {@code NONE}，
     * 于是崩溃收口会把它当成"没有副作用"直接判 FAILED——正是 B7 要消灭的那个漏判。</p>
     */
    @Transactional
    public void attachEffect(AgentToolInvocation invocation, String effectId) {
        if (!enabled || invocation == null || invocation.getId() == null
                || !StringUtils.hasText(effectId)) {
            return;
        }
        invocation.setEffectId(effectId);
        invocation.setEffectState(AgentToolInvocation.EFFECT_STATE_PREPARED);
        invocationMapper.update(null, new LambdaUpdateWrapper<AgentToolInvocation>()
                .eq(AgentToolInvocation::getId, invocation.getId())
                .set(AgentToolInvocation::getEffectId, effectId)
                .set(AgentToolInvocation::getEffectState, AgentToolInvocation.EFFECT_STATE_PREPARED));
    }

    /**
     * 标记这次调用"请求已交给工具"，也就是**越过了副作用边界**。
     *
     * <p><b>调用点必须在 {@code tool.execute(...)} 之前，而且两个执行点都要改：</b>
     * 管道的 {@code invoke()}，以及审批恢复的
     * {@code AgentConversationService#resumeApprovedInvocation()}——后者直接执行那条已批准的调用，
     * **绕开了管道**。顺序不能反：崩在"标记"与"发送"之间只会产生一个假 {@code DISPATCHED}
     * （多一次无谓对账，安全方向），反过来则会漏掉一次真实写入（要命方向）。
     * 少改一个执行点，那条路径上的崩溃就会退化成"漏判"，而它只在崩溃时才显形。</p>
     *
     * <p>只对带 {@code effect_id} 的调用生效：只读工具没有副作用阶段，给它写
     * {@code DISPATCHED} 会让崩溃收口把一次纯读当成待对账的写。</p>
     *
     * <p>条件更新限定在 {@code PREPARED} 上，所以它幂等。零行不是错误——要么这次调用没有 Effect，
     * 要么另一个线程已经标记过了；两种情况下内存对象都该同步成已派发。</p>
     */
    @Transactional
    public void markEffectDispatched(AgentToolInvocation invocation) {
        if (!enabled || invocation == null || invocation.getId() == null
                || !StringUtils.hasText(invocation.getEffectId())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int changed = invocationMapper.update(null, new LambdaUpdateWrapper<AgentToolInvocation>()
                .eq(AgentToolInvocation::getId, invocation.getId())
                .eq(AgentToolInvocation::getEffectState, AgentToolInvocation.EFFECT_STATE_PREPARED)
                .set(AgentToolInvocation::getEffectState, AgentToolInvocation.EFFECT_STATE_DISPATCHED)
                .set(AgentToolInvocation::getEffectDispatchedAt, now));
        if (changed != 1) {
            log.debug("agent effect dispatch mark skipped invocationId={} effectState={}",
                    invocation.getInvocationId(), invocation.getEffectState());
        }
        // 无论是否命中都同步内存：没命中的情形是"已经是 DISPATCHED 了"，那也正是现在的真相。
        invocation.setEffectState(AgentToolInvocation.EFFECT_STATE_DISPATCHED);
        invocation.setEffectDispatchedAt(now);
    }

    /**
     * 标记已拿到远端成功凭据。
     *
     * <p>由 {@link #completeInvocation} 调用，所以两条执行路径（管道 / 审批恢复）都覆盖到，
     * 不需要各自再写一次。</p>
     *
     * <p>守卫接受 {@code PREPARED} 与 {@code DISPATCHED} 两者，而不是只接受后者。
     * 理由是"工具成功了"比"中间那次标记落了库"是**更强**的事实：若某个执行点漏了
     * {@code markEffectDispatched}，严格守卫会让这次调用停在 {@code PREPARED} 而状态却是
     * {@code SUCCEEDED}——一个自相矛盾的行，运维看到会以为"写成功了但没发出去"。
     * 放宽不会掩盖那个漏标：真正的危险方向是"发出去了却记成没发出"，而那只发生在
     * **崩溃**路径上（成功路径最终一定会走到这里），所以漏标仍然由两个执行点的测试来守。</p>
     */
    @Transactional
    public void markEffectConfirmed(AgentToolInvocation invocation) {
        if (!enabled || invocation == null || invocation.getId() == null
                || !StringUtils.hasText(invocation.getEffectId())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int changed = invocationMapper.update(null, new LambdaUpdateWrapper<AgentToolInvocation>()
                .eq(AgentToolInvocation::getId, invocation.getId())
                .in(AgentToolInvocation::getEffectState,
                        AgentToolInvocation.EFFECT_STATE_PREPARED,
                        AgentToolInvocation.EFFECT_STATE_DISPATCHED)
                .set(AgentToolInvocation::getEffectState, AgentToolInvocation.EFFECT_STATE_CONFIRMED)
                .set(AgentToolInvocation::getEffectConfirmedAt, now));
        if (changed != 1) {
            log.debug("agent effect confirmation mark skipped invocationId={} effectState={}",
                    invocation.getInvocationId(), invocation.getEffectState());
        }
        invocation.setEffectState(AgentToolInvocation.EFFECT_STATE_CONFIRMED);
        invocation.setEffectConfirmedAt(now);
    }

    @Transactional
    public void completeInvocation(AgentToolInvocation invocation, JsonNode result) {
        transitionInvocation(invocation, AgentToolInvocation.STATUS_SUCCEEDED,
                result == null ? null : result.toString(), null);
        // 先记"拿到远端凭据"，再结算 Effect：两件事的因果顺序就是这个顺序，
        // 反过来写会让一条 CONFIRMED 的调用挂在一个已经 SETTLED 的 Effect 上。
        markEffectConfirmed(invocation);
        settleEffectOf(invocation);
    }

    @Transactional
    public void failInvocation(AgentToolInvocation invocation, String reason) {
        transitionInvocation(invocation, AgentToolInvocation.STATUS_FAILED, null, reason);
    }

    @Transactional
    public void cancelInvocation(AgentToolInvocation invocation) {
        transitionInvocation(invocation, AgentToolInvocation.STATUS_CANCELLED, null, "用户取消");
    }

    /** Moves a durable invocation into the non-executing approval wait state. */
    @Transactional
    public void waitForApproval(AgentToolInvocation invocation) {
        if (!enabled || invocation == null || invocation.getId() == null) return;
        int changed = invocationMapper.update(null, new LambdaUpdateWrapper<AgentToolInvocation>()
                .eq(AgentToolInvocation::getId, invocation.getId())
                .eq(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_RUNNING)
                .set(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_WAITING_APPROVAL));
        if (changed != 1) throw new IllegalStateException("工具调用审批状态已变化");
        invocation.setStatus(AgentToolInvocation.STATUS_WAITING_APPROVAL);
    }

    /**
     * 把 Run 置为等审批，并**结算这一段活跃时长**。
     *
     * <p>等待审批不算活跃时间——用户可能出去吃饭三小时才批准。不在这里结算的话，
     * 那三小时会被算进"这个任务跑了多久"，于是一个刚开工一分钟的任务在恢复时直接判超时。</p>
     */
    @Transactional
    public void waitRunForApproval(Long conversationId, Long userId, long runVersion) {
        if (!enabled) return;
        AgentRun run = findRun(conversationId, runVersion);
        if (run == null) throw new AgentRunSupersededException(conversationId, runVersion);
        long settledActiveMs = run.getActiveDurationMs() == null ? 0L : Math.max(0L, run.getActiveDurationMs());
        if (run.getLastRunningAt() != null) {
            settledActiveMs += elapsedMillisSince(run.getLastRunningAt(), LocalDateTime.now());
        }
        int changed = runMapper.update(null, new LambdaUpdateWrapper<AgentRun>()
                .eq(AgentRun::getId, run.getId())
                .eq(AgentRun::getUserId, userId)
                .eq(AgentRun::getStatus, AgentRun.STATUS_RUNNING)
                .set(AgentRun::getStatus, AgentRun.STATUS_WAITING_APPROVAL)
                .set(AgentRun::getActiveDurationMs, settledActiveMs)
                .setSql("last_running_at = NULL"));
        if (changed != 1) throw new AgentRunSupersededException(conversationId, runVersion);
    }

    /** Claims an approved invocation exactly once before its side effect begins. */
    @Transactional
    public AgentToolInvocation startApprovedInvocation(Long invocationDbId) {
        if (!enabled) throw new IllegalStateException("审批执行需要启用执行日志");
        AgentToolInvocation invocation = invocationMapper.selectById(invocationDbId);
        if (invocation == null) throw new IllegalStateException("审批关联的工具调用不存在");
        int changed = invocationMapper.update(null, new LambdaUpdateWrapper<AgentToolInvocation>()
                .eq(AgentToolInvocation::getId, invocationDbId)
                .eq(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_WAITING_APPROVAL)
                .set(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_RUNNING));
        if (changed != 1) throw new IllegalStateException("工具调用审批状态已变化");
        invocation.setStatus(AgentToolInvocation.STATUS_RUNNING);
        return invocation;
    }

    /** Cancels a still-pending invocation after its owner rejects the exact requested action. */
    @Transactional
    public void rejectWaitingInvocation(Long invocationDbId, String reason) {
        if (!enabled) return;
        int changed = invocationMapper.update(null, new LambdaUpdateWrapper<AgentToolInvocation>()
                .eq(AgentToolInvocation::getId, invocationDbId)
                .eq(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_WAITING_APPROVAL)
                .set(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_CANCELLED)
                .set(AgentToolInvocation::getErrorMessage, limit(reason, 1000))
                .set(AgentToolInvocation::getCompletedAt, LocalDateTime.now()));
        if (changed != 1) throw new IllegalStateException("工具调用审批状态已变化");
    }

    /**
     * 按主键读一条工具调用记录。
     *
     * <p>给审批决定用：判断"用户批准的动作"与"当前这条 invocation 的参数"是否还是同一件事，
     * 并取出它的 {@code effectId} 以便在拒绝时把那个业务动作一并作废。</p>
     *
     * <p>返回 {@link Optional} 而不是抛异常——记录不存在是一个要转成业务拒绝的常见情况
     * （invocation 随 Run 被级联删除），不是程序错误。</p>
     */
    @Transactional(readOnly = true)
    public Optional<AgentToolInvocation> findInvocation(Long invocationDbId) {
        if (!enabled || invocationDbId == null) return Optional.empty();
        return Optional.ofNullable(invocationMapper.selectById(invocationDbId));
    }

    /**
     * 这个业务动作被明确放弃了（例如审批被拒），后续不再采纳。
     *
     * <p>不放弃它的话，模型下一次重发同样的调用会接上这个仍然 OPEN 的 Effect——
     * 那等于绕过了刚刚被拒绝的那次审批。</p>
     */
    @Transactional
    public void abandonEffect(String effectId) {
        if (effectService == null) return;
        effectService.abandonEffect(effectId);
    }

    /** Completes a run that was deliberately paused for approval rather than actively executing. */
    @Transactional
    public void completeWaitingRun(Long runId, Long userId) {
        if (!enabled) return;
        int changed = runMapper.update(null, new LambdaUpdateWrapper<AgentRun>()
                .eq(AgentRun::getId, runId)
                .eq(AgentRun::getUserId, userId)
                .eq(AgentRun::getStatus, AgentRun.STATUS_WAITING_APPROVAL)
                .set(AgentRun::getStatus, AgentRun.STATUS_COMPLETED)
                .set(AgentRun::getCompletedAt, LocalDateTime.now()));
        if (changed != 1) throw new IllegalStateException("智能体审批运行状态已变化");
    }

    /** Returns a bounded batch of interrupted tool calls for read-only business reconciliation. */
    @Transactional(readOnly = true)
    public List<AgentToolInvocation> unknownInvocations(int batchSize) {
        if (!enabled) return List.of();
        return invocationMapper.selectList(new LambdaQueryWrapper<AgentToolInvocation>()
                .eq(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_UNKNOWN)
                .orderByAsc(AgentToolInvocation::getUpdatedAt)
                .last("LIMIT " + Math.min(Math.max(batchSize, 1), 100)));
    }

    /** Finds approval-resumed calls whose worker disappeared before it could settle an outcome. */
    @Transactional(readOnly = true)
    public List<AgentToolInvocation> runningInvocationsBefore(LocalDateTime cutoff, int batchSize) {
        if (!enabled) return List.of();
        return invocationMapper.selectList(new LambdaQueryWrapper<AgentToolInvocation>()
                .eq(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_RUNNING)
                .le(AgentToolInvocation::getUpdatedAt, cutoff)
                .orderByAsc(AgentToolInvocation::getUpdatedAt)
                .last("LIMIT " + Math.min(Math.max(batchSize, 1), 100)));
    }

    /** Converts only an expired approved execution into an explicit reconciliation state. */
    @Transactional
    public void markInvocationUnknown(AgentToolInvocation invocation, String reason) {
        if (!enabled || invocation == null || invocation.getId() == null) return;
        invocationMapper.update(null, new LambdaUpdateWrapper<AgentToolInvocation>()
                .eq(AgentToolInvocation::getId, invocation.getId())
                .eq(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_RUNNING)
                .set(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_UNKNOWN)
                .set(AgentToolInvocation::getErrorMessage, limit(reason, 1000))
                .set(AgentToolInvocation::getCompletedAt, LocalDateTime.now()));
    }

    /** Marks an interrupted invocation as settled only when its owning tool proved its effect exists. */
    @Transactional
    public void reconcileInvocation(AgentToolInvocation invocation, JsonNode result) {
        if (!enabled || invocation == null || invocation.getId() == null) return;
        invocationMapper.update(null, new LambdaUpdateWrapper<AgentToolInvocation>()
                .eq(AgentToolInvocation::getId, invocation.getId())
                .eq(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_UNKNOWN)
                .set(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_SUCCEEDED)
                .set(AgentToolInvocation::getResultJson, result == null ? null : result.toString())
                .set(AgentToolInvocation::getErrorMessage, null)
                .set(AgentToolInvocation::getCompletedAt, LocalDateTime.now()));
        // 对账查到了远端确实有这个副作用，与直接拿到成功结果是一回事：它已经发生了，不再可被采纳。
        settleEffectOf(invocation);
    }

    /**
     * 一次调用拿到成功结果 = 这个业务动作已经发生，副作用不再可被采纳。
     *
     * <p>只有两个出口会走到这里：{@link #completeInvocation}（工具直接返回成功）与
     * {@link #reconcileInvocation}（对账证明远端已有）。两处都调这一个方法——
     * 各写一遍的话，漏掉对账那一条的后果是"对账恢复的动作仍可被采纳"，
     * 而那恰好是最需要防止重复的一类。</p>
     */
    private void settleEffectOf(AgentToolInvocation invocation) {
        if (effectService == null || invocation == null) return;
        effectService.settleEffect(invocation.getEffectId());
    }

    /** Closes the run beside the existing conversation terminal transition. */
    @Transactional
    public void completeRun(Long conversationId, Long userId, long runVersion) {
        transitionRun(conversationId, userId, runVersion, AgentRun.STATUS_COMPLETED, null);
    }

    /**
     * 按主键读一条执行记录。
     *
     * <p>给 {@code resume()} 用：调用方手上只有 {@code runId}（审批恢复来自
     * {@code agent_approval.run_id}，自动恢复来自调度器扫出来的行），需要从它反查出
     * 所属会话、代次与意图周期。返回 {@link Optional} 而不是抛异常——"记录不存在"是一个
     * 需要被转成业务拒绝的常见情况（Run 被级联删除），不是程序错误。</p>
     */
    @Transactional(readOnly = true)
    public Optional<AgentRun> findRun(Long runId) {
        if (!enabled || runId == null) return Optional.empty();
        return Optional.ofNullable(runMapper.selectById(runId));
    }

    /**
     * 从**任意可恢复态**重新进入 RUNNING。
     *
     * <p>条件更新限定在调用方读到的那个状态上，所以它同时是"状态自读入以来没变过"的 CAS：
     * 另一次恢复抢先把状态迁走时这里命中 0 行，调用方据此拒绝这次恢复，而不是把两次恢复
     * 叠在一起。</p>
     *
     * <p><b>刻意不写 {@code intent_epoch}。</b>它只改状态与 {@code last_running_at}，周期字段
     * 原样留着——"复用同一意图"就是"不写"这个动作本身。给这里补一个 setter 会让 resume 静默
     * 变成新周期，于是崩溃后的重试不再被识别为重试，同一个副作用被写第二遍。</p>
     *
     * <p>审批恢复与调度器自动恢复都走这里：审批不是"另一条路"，只是起始状态固定为
     * {@code WAITING_APPROVAL}。</p>
     *
     * @return 是否由本次调用完成迁移
     */
    @Transactional
    public boolean resumeRun(Long runId, Long userId, String fromStatus) {
        if (!enabled) return false;
        int changed = runMapper.update(null, new LambdaUpdateWrapper<AgentRun>()
                .eq(AgentRun::getId, runId)
                .eq(AgentRun::getUserId, userId)
                .eq(AgentRun::getStatus, fromStatus)
                .set(AgentRun::getStatus, AgentRun.STATUS_RUNNING)
                // 重新进入 RUNNING 就重新开始计活跃时长（等待审批/中断的时长不计入）。
                .set(AgentRun::getLastRunningAt, LocalDateTime.now()));
        return changed == 1;
    }

    /**
     * 记下"这个 Run 已经自动恢复过一次"。
     *
     * <p><b>必须在提交自动恢复之前写。</b>写在后头的话，"提交成功但记账失败"或"进程恰好死在
     * 两者之间"会让这个 Run 每次被扫到都再恢复一次——而"最多一次"正是防止一个必然失败的 Run
     * （依赖的工具坏了）被调度器无限重跑的唯一屏障。写在前面最多浪费一次恢复机会（安全方向），
     * 写在后面则可能永远转下去。</p>
     *
     * <p>{@code isNull} 让它成为一次性的 CAS：并发的两个调度实例只有一个能抢到。</p>
     *
     * @return 是否是本次调用占用了这个名额
     */
    @Transactional
    public boolean markAutoResumed(Long runId, Long userId) {
        if (!enabled) return false;
        int changed = runMapper.update(null, new LambdaUpdateWrapper<AgentRun>()
                .eq(AgentRun::getId, runId)
                .eq(AgentRun::getUserId, userId)
                .isNull(AgentRun::getAutoResumedAt)
                .set(AgentRun::getAutoResumedAt, LocalDateTime.now()));
        return changed == 1;
    }

    /**
     * 最近一步里是否存在"已成功、但模型还没机会看到结果"的工具调用。
     *
     * <p>审批可能恰好落在最后一步：那条调用会被执行并落库（见
     * {@code AgentConversationService#resumeApprovedInvocation}），但 {@code nextStepNo} 已经越过
     * {@code maxSteps}，编排循环体一次都不进，用户看到的是"推理步数已达上限"——
     * 而他批准的操作其实已经执行完了，只是模型没机会说明。</p>
     *
     * <p>只在"进入循环时就已经超预算"的情况下被调用，所以正常的自然耗尽不会走到这里，
     * 也不会多出一次查询。判据取"最近一步（step_no 最大）里有 SUCCEEDED 的调用"：
     * 审批恢复时那条调用就挂在它原本所属的那一步上，而这一步正是最后一步。</p>
     */
    @Transactional(readOnly = true)
    public boolean hasSettledInvocationInLatestStep(Long conversationId, long runVersion) {
        if (!enabled) return false;
        AgentRun run = findRun(conversationId, runVersion);
        if (run == null) return false;
        List<AgentStep> steps = stepMapper.selectList(new LambdaQueryWrapper<AgentStep>()
                .eq(AgentStep::getRunId, run.getId()));
        if (steps == null || steps.isEmpty()) return false;
        Long latestStepId = steps.stream()
                .filter(step -> step.getStepNo() != null && step.getId() != null)
                .max(java.util.Comparator.comparing(AgentStep::getStepNo))
                .map(AgentStep::getId)
                .orElse(null);
        if (latestStepId == null) return false;
        Long settled = invocationMapper.selectCount(new LambdaQueryWrapper<AgentToolInvocation>()
                .eq(AgentToolInvocation::getStepId, latestStepId)
                .eq(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_SUCCEEDED));
        return settled != null && settled > 0L;
    }

    /** Returns the next unused one-based model-step number for an active run. */
    @Transactional(readOnly = true)
    public int nextStepNo(Long conversationId, long runVersion) {
        if (!enabled) return 1;
        AgentRun run = findRun(conversationId, runVersion);
        if (run == null) throw new AgentRunSupersededException(conversationId, runVersion);
        List<AgentStep> steps = stepMapper.selectList(new LambdaQueryWrapper<AgentStep>()
                .eq(AgentStep::getRunId, run.getId()));
        return steps == null ? 1 : steps.stream()
                .map(AgentStep::getStepNo)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .map(stepNo -> stepNo + 1)
                .orElse(1);
    }

    /** Aggregates completed steps before a resumed loop so approval pauses cannot omit earlier billing. */
    @Transactional(readOnly = true)
    public Optional<AgentRunUsage> usageBeforeStep(Long conversationId, long runVersion, int nextStepNo) {
        if (!enabled) return Optional.empty();
        AgentRun run = findRun(conversationId, runVersion);
        if (run == null) return Optional.empty();
        List<AgentStep> steps = stepMapper.selectList(new LambdaQueryWrapper<AgentStep>()
                .eq(AgentStep::getRunId, run.getId())
                .lt(AgentStep::getStepNo, nextStepNo)
                .eq(AgentStep::getStatus, AgentStep.STATUS_COMPLETED));
        if (steps == null || steps.isEmpty()) return Optional.empty();
        long input = 0L;
        long cached = 0L;
        long output = 0L;
        String model = null;
        boolean mixedModels = false;
        for (AgentStep step : steps) {
            input += Math.max(0L, valueOrZero(step.getInputTokens()));
            cached += Math.max(0L, valueOrZero(step.getCachedTokens()));
            output += Math.max(0L, valueOrZero(step.getOutputTokens()));
            if (step.getModelName() == null || step.getModelName().isBlank()) continue;
            if (model == null) model = step.getModelName();
            else if (!model.equals(step.getModelName())) mixedModels = true;
        }
        return Optional.of(new AgentRunUsage(new ModelUsage(input, cached, output), mixedModels ? null : model));
    }

    @Transactional
    public void failRun(Long conversationId, Long userId, long runVersion, String reason) {
        transitionRun(conversationId, userId, runVersion, AgentRun.STATUS_FAILED, reason);
    }

    /**
     * 用户请求停止并已收口。
     *
     * <p>与 {@link #failRun} 分开落库：以前取消也走 {@code completeRun}，于是"用户按了停止"
     * 在 {@code agent_run.status} 里被记成 {@code COMPLETED}，与"模型正常做完"无法区分。</p>
     */
    @Transactional
    public void cancelRun(Long conversationId, Long userId, long runVersion) {
        transitionRun(conversationId, userId, runVersion, AgentRun.STATUS_CANCELLED, null);
    }

    /**
     * 持有者进程消失、执行未收口；由 {@code AgentRunStaleReaper} 触发。
     *
     * @param lastAliveAt 「最后已知存活时刻」。**必须传会话租约的 {@code updated_at}，
     *                    不要传 {@code now()}**：进程死掉到被扫描出来之间可能隔着几十分钟，
     *                    用 now 结算会把这段"死掉的时间"也记成活跃时长，一次崩溃白送一截预算。
     *                    租约续期每 {@code min(60s, stale/3)} 触碰一次那个时钟，
     *                    所以它是存活时刻的一个紧上界。
     *                    <p>注意**不是** {@code agent_run.updated_at}：它只在状态迁移和开头那次
     *                    {@code recordSkillOffer} 时更新，一个健康跑了一小时的任务在它看来是
     *                    "一小时没动过"。</p>
     */
    @Transactional
    public void interruptRun(Long conversationId, Long userId, long runVersion, String reason,
                             LocalDateTime lastAliveAt) {
        transitionRun(conversationId, userId, runVersion, AgentRun.STATUS_INTERRUPTED, reason, lastAliveAt);
    }

    /** 预算耗尽，可继续对话让模型接着收口。 */
    @Transactional
    public void exhaustBudgetRun(Long conversationId, Long userId, long runVersion) {
        transitionRun(conversationId, userId, runVersion, AgentRun.STATUS_BUDGET_EXHAUSTED, null);
    }

    private void transitionStep(AgentStep step, String status, String reason) {
        if (!enabled || step == null || step.getId() == null) return;
        stepMapper.update(null, new LambdaUpdateWrapper<AgentStep>()
                .eq(AgentStep::getId, step.getId())
                .eq(AgentStep::getStatus, AgentStep.STATUS_RUNNING)
                .set(AgentStep::getStatus, status)
                .set(AgentStep::getFailureReason, limit(reason, 1000))
                .set(AgentStep::getCompletedAt, LocalDateTime.now()));
    }

    private void transitionInvocation(AgentToolInvocation invocation, String status,
                                      String result, String error) {
        if (!enabled || invocation == null || invocation.getId() == null) return;
        invocationMapper.update(null, new LambdaUpdateWrapper<AgentToolInvocation>()
                .eq(AgentToolInvocation::getId, invocation.getId())
                .eq(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_RUNNING)
                .set(AgentToolInvocation::getStatus, status)
                .set(AgentToolInvocation::getResultJson, result)
                .set(AgentToolInvocation::getErrorMessage, limit(error, 1000))
                .set(AgentToolInvocation::getCompletedAt, LocalDateTime.now()));
    }

    private void transitionRun(Long conversationId, Long userId, long runVersion, String status,
                               String reason) {
        transitionRun(conversationId, userId, runVersion, status, reason, null);
    }

    /**
     * 运行状态迁移，并在**离开 RUNNING** 时结算这一段活跃时长。
     *
     * <p>结算的算术放在 Java 里而不是写成 {@code TIMESTAMPDIFF} 的 SQL，是因为崩溃路径需要一个
     * 自定义的截止时刻（见 {@link #interruptRun}），而把时间戳拼进 SQL 字符串既难读也容易出错。
     * 读-算-写在这里是安全的：这条 UPDATE 的条件是 {@code status = RUNNING}，
     * 任何别的写者都必须先把状态迁走才能改这行，所以只有一次迁移能命中；
     * 而 RUNNING 期间没有任何别的写者会动 {@code active_duration_ms}。</p>
     *
     * @param settleUntil 结算到哪个时刻。{@code null} 表示"现在"（进程还活着，正常收口）；
     *                    崩溃恢复路径传入"最后已知存活时刻"，否则会把进程死后的整段时间
     *                    也算成活跃时间，于是一次崩溃白送几十分钟预算。
     */
    private void transitionRun(Long conversationId, Long userId, long runVersion, String status,
                               String reason, LocalDateTime settleUntil) {
        if (!enabled) return;
        AgentRun run = findRun(conversationId, runVersion);
        if (run == null) return;
        LocalDateTime now = LocalDateTime.now();
        long settledActiveMs = run.getActiveDurationMs() == null ? 0L : Math.max(0L, run.getActiveDurationMs());
        if (run.getLastRunningAt() != null) {
            settledActiveMs += elapsedMillisSince(run.getLastRunningAt(),
                    settleUntil == null ? now : settleUntil);
        }
        int changed = runMapper.update(null, new LambdaUpdateWrapper<AgentRun>()
                .eq(AgentRun::getId, run.getId())
                .eq(AgentRun::getUserId, userId)
                .eq(AgentRun::getStatus, AgentRun.STATUS_RUNNING)
                .set(AgentRun::getStatus, status)
                .set(AgentRun::getActiveDurationMs, settledActiveMs)
                .setSql("last_running_at = NULL")
                .set(AgentRun::getFailureReason, limit(reason, 1000))
                .set(AgentRun::getCompletedAt, now));
        if (changed == 1) {
            settleInFlightWork(run.getId(), status, reason);
        }
    }

    /**
     * 运行进入终态时收口在途记录。**三种处理不能合并。**
     *
     * <ul>
     *   <li><b>{@code FAILED} / {@code INTERRUPTED}</b>——持有者是在**不确定**状态下消失的
     *       （异常、崩溃、被 kill）。在途 invocation 按 {@code effect_state} 分流：越过了
     *       副作用边界的判 {@code UNKNOWN} 而不是 {@code FAILED}——请求可能已经发到远端了，
     *       标成"已失败"会让对账器永远不去查它，用户再发一次就是第二次写入。这条注释原本
     *       只挂在 FAILED 上，但它描述的是"持有者消失"，所以 INTERRUPTED 同样适用。</li>
     *   <li><b>{@code CANCELLED}</b>——收口在途 step，invocation 同样按 {@code effect_state}
     *       分流（见下）。取消路径此前完全不清在途 step，于是它们会永久停在 {@code RUNNING}。</li>
     *   <li><b>{@code COMPLETED} / {@code BUDGET_EXHAUSTED}</b>——正常退出，不该有在途记录。
     *       真出现了说明编排循环漏收口，这里只记日志不代它收：静默补收会把一个真实的
     *       循环缺陷藏起来。</li>
     * </ul>
     *
     * <p><b>B7：invocation 不再按"谁把它停的"一刀切，而是按 {@code effect_state} 问
     * "远端到底有没有可能已经发生"。</b>在 {@code effect_state} 落地之前，取消路径只能把
     * 在途 invocation 整个放过、留给执行管道，因为"写提交了没有"这个问题**无法回答**；
     * 而管道在协作式取消点之后可能已经被 kill，根本走不到收口那一步。于是 §1.3 那个
     * "取消落在写已提交之后"的重复写窗口就一直开着。现在这个状态列直接回答了它，
     * 所以取消路径可以自己收口，不必再赌管道还能跑。</p>
     *
     * <p>两条 UPDATE 合起来**穷尽**所有取值，且分流方向刻意偏向安全一侧。判据不是"阶段值等于
     * 什么"，而是**"远端确定什么都没发生吗"**——只有两种情况能回答"是"：</p>
     * <ul>
     *   <li><b>没有 {@code effect_id}</b>——这次调用压根没有业务副作用身份（只读工具，或 B6 之前
     *       落库的存量行）。没有远端写，自然不需要对账。</li>
     *   <li><b>阶段是 {@code PREPARED}</b>——意图已落库，但请求还没交给工具。这是**确切知道**
     *       远端什么都没发生的情形。</li>
     * </ul>
     * <p>其余一律 {@code UNKNOWN} 交给对账器。特别注意 <b>{@code NONE} 单独出现不算"安全"</b>：
     * 阶段列是 B7 才加的，存量行保持默认 {@code NONE}，而"已有 {@code effect_id} 却停在
     * {@code NONE}"只可能出自 B6 与 B7 之间的部署窗口——那时请求可能已经发出去了。
     * 把它当成"没发出"正是这次要消灭的漏判，所以判据里必须带上 {@code effect_id}。</p>
     *
     * <p>写成"可安全终结"的**白名单**而不是"已越界"的黑名单，是因为两种错法的代价不对称：
     * 把一次真实写入误判成可终结，会让对账器永远不去看它，用户重发就是第二次写（要命方向）；
     * 反过来多查一次只是多一次无谓的远端查询（安全方向）。将来若新增第五个阶段，
     * 它会自动落进"要查"这一侧。</p>
     *
     * <p>{@code CONFIRMED} 出现在这条路径上其实是**不可达**的：{@code markEffectConfirmed}
     * 与状态置 {@code SUCCEEDED} 同处一个事务（见 {@link #completeInvocation}），落库后
     * 不可能出现"状态还是 {@code RUNNING} 而阶段已 {@code CONFIRMED}"的行。也就是说
     * D2 期望里的"CONFIRMED → SUCCEEDED"是靠那条事务边界**结构性**成立的，不是靠这里
     * 再补一个分支。之所以仍把它归到 {@code UNKNOWN} 一侧，是为了将来有人把那两件事拆成
     * 两个事务时，默认落到安全方向，而不是静默终结。</p>
     */
    private void settleInFlightWork(Long runId, String status, String reason) {
        boolean workerVanished = AgentRun.STATUS_FAILED.equals(status)
                || AgentRun.STATUS_INTERRUPTED.equals(status);
        boolean userStopped = AgentRun.STATUS_CANCELLED.equals(status);
        if (!workerVanished && !userStopped) {
            long stranded = stepMapper.selectCount(new LambdaQueryWrapper<AgentStep>()
                    .eq(AgentStep::getRunId, runId)
                    .eq(AgentStep::getStatus, AgentStep.STATUS_RUNNING));
            if (stranded > 0L) {
                log.warn("agent run settled as {} with {} step(s) still RUNNING; the loop left them open"
                                + " runId={} reason={}",
                        status, stranded, runId, reason);
            }
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        String boundedReason = limit(reason, 1000);
        stepMapper.update(null, new LambdaUpdateWrapper<AgentStep>()
                .eq(AgentStep::getRunId, runId)
                .eq(AgentStep::getStatus, AgentStep.STATUS_RUNNING)
                .set(AgentStep::getStatus, userStopped
                        ? AgentStep.STATUS_CANCELLED : AgentStep.STATUS_FAILED)
                .set(AgentStep::getFailureReason, boundedReason)
                .set(AgentStep::getCompletedAt, now));

        // ① 有业务副作用身份、且阶段不是 PREPARED → 远端可能已经发生，一律交给对账，绝不判终态。
        //    这条同时覆盖 DISPATCHED / CONFIRMED，以及"有 effect_id 却停在 NONE"的存量行。
        int needsReconciliation = invocationMapper.update(null, new LambdaUpdateWrapper<AgentToolInvocation>()
                .eq(AgentToolInvocation::getRunId, runId)
                .eq(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_RUNNING)
                .isNotNull(AgentToolInvocation::getEffectId)
                .ne(AgentToolInvocation::getEffectState, AgentToolInvocation.EFFECT_STATE_PREPARED)
                .set(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_UNKNOWN)
                .set(AgentToolInvocation::getErrorMessage, boundedReason)
                .set(AgentToolInvocation::getCompletedAt, now));

        // ② 没有副作用身份（只读），或明确还没把请求交出去 → 可以安全终结。
        //    与 ① 互斥（① 要求 effect_id 非空）且合起来穷尽，所以不会有残留 RUNNING。
        int settled = invocationMapper.update(null, new LambdaUpdateWrapper<AgentToolInvocation>()
                .eq(AgentToolInvocation::getRunId, runId)
                .eq(AgentToolInvocation::getStatus, AgentToolInvocation.STATUS_RUNNING)
                .and(w -> w.isNull(AgentToolInvocation::getEffectId)
                        .or().eq(AgentToolInvocation::getEffectState,
                                AgentToolInvocation.EFFECT_STATE_PREPARED))
                .set(AgentToolInvocation::getStatus, userStopped
                        ? AgentToolInvocation.STATUS_CANCELLED : AgentToolInvocation.STATUS_FAILED)
                .set(AgentToolInvocation::getErrorMessage, boundedReason)
                .set(AgentToolInvocation::getCompletedAt, now));

        if (needsReconciliation > 0 || settled > 0) {
            log.info("agent run settled as {}; in-flight invocations collected runId={}"
                            + " needsReconciliation={} terminallySettled={}",
                    status, runId, needsReconciliation, settled);
        }
    }

    private AgentRun requireRunningRun(Long conversationId, Long userId, long runVersion) {
        requireActiveConversation(conversationId, userId, runVersion);
        AgentRun run = findRun(conversationId, runVersion);
        if (run == null || !AgentRun.STATUS_RUNNING.equals(run.getStatus())) {
            throw new AgentRunSupersededException(conversationId, runVersion);
        }
        return run;
    }

    private void requireActiveConversation(Long conversationId, Long userId, long runVersion) {
        var conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !userId.equals(conversation.getUserId())
                || !Long.valueOf(runVersion).equals(conversation.getRunVersion())
                || !"running".equals(conversation.getStatus())) {
            throw new AgentRunSupersededException(conversationId, runVersion);
        }
    }

    /**
     * 按会话与代次读当前的执行记录。
     *
     * <p>给"用户主动点继续"这条入口用：HTTP 路径上只有会话 id，而 {@code resume()} 要的是 run id。
     * 这个组合由唯一键 {@code uk_agent_run_conversation_version} 保证至多一行，所以不存在
     * "取到的是哪一条"的歧义。</p>
     *
     * <p><b>刻意与 {@link #findRun(Long)} 分开命名。</b>两个重载都以一个 Long 开头，
     * 混用一次就变成"拿会话 id 当 run id 查"——而那种错误查不到东西时的表现是
     * "这个执行不存在"，与真的不存在无法区分，排查方向从一开始就是错的。</p>
     *
     * @return 该会话该代次的执行记录；不存在或本实例未启用日志时为空
     */
    public Optional<AgentRun> findCurrentRun(Long conversationId, long runVersion) {
        if (!enabled || conversationId == null) return Optional.empty();
        return Optional.ofNullable(findRun(conversationId, runVersion));
    }

    private AgentRun findRun(Long conversationId, long runVersion) {
        return runMapper.selectOne(new LambdaQueryWrapper<AgentRun>()
                .eq(AgentRun::getConversationId, conversationId)
                .eq(AgentRun::getRunVersion, runVersion));
    }

    private AgentStep disabledStep(int stepNo) {
        AgentStep step = new AgentStep();
        step.setId(-1L);
        step.setStepNo(stepNo);
        return step;
    }

    private AgentToolInvocation disabledInvocation(String invocationId, String toolName) {
        AgentToolInvocation invocation = new AgentToolInvocation();
        invocation.setId(-1L);
        invocation.setInvocationId(invocationId);
        invocation.setToolName(toolName);
        return invocation;
    }

    private String limit(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }
}

/** Persisted aggregate of one agent run's completed model usage. */
record AgentRunUsage(ModelUsage usage, String model) {
}
