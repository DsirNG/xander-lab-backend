package com.xander.lab.agent.application;

import com.xander.lab.agent.domain.RunBudget;
import com.xander.lab.agent.domain.RunUsage;
import com.xander.lab.agent.infrastructure.AgentConversationProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 预算判定：把"还能不能继续"从循环条件里提出来，做成一个**能命名、能断言、能单独打日志**的决策。
 *
 * <p>以前预算判定藏在 {@code for (step = nextStepNo; step <= maxSteps; step++)} 的条件子句里。
 * 那样写有三个后果，都会在加了更多维度后立刻放大：</p>
 *
 * <ul>
 *   <li><b>边界处已批准的执行没有收口路径。</b>审批若落在最后一步，那条调用会被执行并落库，
 *       但循环体一次都不进，用户看到的是"步数已达上限"——而他批准的操作其实已经做完了。</li>
 *   <li><b>耗尽原因不可区分。</b>步数用尽 / 工具次数用尽 / 时长用尽会全部塌进同一条文案。</li>
 *   <li><b>前端拿不到结构化终态。</b>只有一条普通 answer 事件。</li>
 * </ul>
 *
 * <p><b>判定顺序是有意的</b>：先判"看得见原因"的维度（步数、工具次数、时长），再判用量维度
 * （token、金额）。多个维度同时越界时，用户需要的是那个他能自己处理的理由——"步数用完了，
 * 继续说一句就行"，而不是"花了 12 万 token"。顺序写死在 {@link #check} 里，不依赖枚举声明序。</p>
 *
 * <p><b>已知边界</b>：检查点只落在步开始 / 工具批次前 / 用量返回后 / 工具完成时。一次超长的模型
 * 调用或工具调用只能在下个检查点被发现，所以**超调上界 = 一次模型调用 + 一批工具**。
 * v1 接受这个上界——要消除它只能靠取消正在飞的请求，而那会引入比它更大的问题
 * （远端可能已经产生副作用）。</p>
 */
@Service
public class RunBudgetGuard {

    /**
     * 把配置折算成一份可冻结的预算。
     *
     * <p>调用点是 {@code claim()} 那一刻，结果写进 {@code agent_run.budget_json}。
     * 之后整个 Run 只读那份快照，不再经过这里。</p>
     */
    public RunBudget budgetFrom(AgentConversationProperties properties) {
        return new RunBudget(
                // 步数**永远**是有限值：它是编排循环的硬上界，不设上限就是一个没有出口的循环。
                // 配成 0 或负数一律按 1 处理，与旧行为（Math.max(1, maxSteps)）一致。
                Math.max(1, properties.getMaxSteps()),
                properties.getMaxToolCalls(),
                0L,
                0L,
                properties.getMaxTotalTokens(),
                properties.getMaxMilliPoints(),
                properties.getMaxActiveMinutes() > 0
                        ? Duration.ofMinutes(properties.getMaxActiveMinutes())
                        : null);
    }

    /**
     * 判定是否已耗尽。
     *
     * @param pendingToolCalls 本次将要执行、但还没落库的工具调用数。检查点 2 用它做**预扣**：
     *                         判定的是"加上这一批会不会超"，而不是"现在已经超了没"。
     *                         其余检查点传 0。
     */
    public Verdict check(RunBudget budget, RunUsage used, int pendingToolCalls) {
        if (budget == null || budget.isUnlimited()) return Verdict.continueRun();

        // 1) 步数：已完成步数已达上限。注意这里判的是"已用 >= 上限"而不是">"——
        //    maxSteps = 20 的含义是"最多跑 20 步"，跑满第 20 步之后就该停，不是还能再跑一步。
        if (budget.limitsSteps() && used.steps() >= budget.maxSteps()) {
            return Verdict.exhausted(Reason.MAX_STEPS);
        }
        // 2) 工具调用总数：预扣。见 check 的 @param 说明。
        long wouldUse = used.toolCalls() + Math.max(0, pendingToolCalls);
        if (budget.limitsToolCalls() && wouldUse > budget.maxToolCalls()) {
            return Verdict.exhausted(Reason.MAX_TOOL_CALLS);
        }
        // 3) 活跃时长：判在"发起下一次远程调用之前"。事后判只能止损，不能阻止。
        if (budget.limitsActiveDuration() && used.activeDuration() != null
                && used.activeDuration().compareTo(budget.maxActiveDuration()) >= 0) {
            return Verdict.exhausted(Reason.MAX_ACTIVE_DURATION);
        }
        // 4) 用量维度放最后：它们是"上一轮已经花掉的"，不是用户能直接处理的理由。
        if (budget.limitsTotalTokens() && used.totalTokens() >= budget.maxTotalTokens()) {
            return Verdict.exhausted(Reason.MAX_TOTAL_TOKENS);
        }
        if (budget.limitsInputTokens() && used.inputTokens() >= budget.maxInputTokens()) {
            return Verdict.exhausted(Reason.MAX_INPUT_TOKENS);
        }
        if (budget.limitsOutputTokens() && used.outputTokens() >= budget.maxOutputTokens()) {
            return Verdict.exhausted(Reason.MAX_OUTPUT_TOKENS);
        }
        if (budget.limitsMilliPoints() && used.milliPoints() >= budget.maxMilliPoints()) {
            return Verdict.exhausted(Reason.MAX_MILLI_POINTS);
        }
        return Verdict.continueRun();
    }

    /** 不带预扣的判定，供步开始前的检查点使用。 */
    public Verdict check(RunBudget budget, RunUsage used) {
        return check(budget, used, 0);
    }

    /**
     * 耗尽的**具体**原因。
     *
     * <p>它存在的理由是"预算耗尽"从来不是一件事：用户能处理的是"步数用完了，继续说一句就行"，
     * 而运维要统计的是"哪一种预算在被撞上"。塌成一个布尔值会让这两件事都做不了。</p>
     */
    public enum Reason {
        /** 步数用尽。用户继续对话即可让模型接着收口。 */
        MAX_STEPS,
        /** 工具调用总数用尽。 */
        MAX_TOOL_CALLS,
        /** 活跃时长用尽。注意不含等待审批的时间。 */
        MAX_ACTIVE_DURATION,
        /** token 总量用尽。 */
        MAX_TOTAL_TOKENS,
        /** 输入 token 用尽。 */
        MAX_INPUT_TOKENS,
        /** 输出 token 用尽。 */
        MAX_OUTPUT_TOKENS,
        /** 金额用尽。 */
        MAX_MILLI_POINTS
    }

    /** 判定结果。{@code reason} 在 {@code exhausted} 为真时必定非空。 */
    public record Verdict(boolean exhausted, Reason reason) {

        private static final Verdict CONTINUE = new Verdict(false, null);

        public static Verdict continueRun() {
            return CONTINUE;
        }

        public static Verdict exhausted(Reason reason) {
            if (reason == null) throw new IllegalArgumentException("预算耗尽必须给出具体原因");
            return new Verdict(true, reason);
        }
    }
}
