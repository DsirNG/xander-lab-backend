package com.xander.lab.agent.domain;

import java.time.Duration;

/**
 * 一次 Run 的资源上限，**冻结在 Run 上**，不在每一步重读配置。
 *
 * <p>为什么必须冻结：运行期改 {@code application.yml} 不该让一个跑了一半的 Run 突然改变判定标准。
 * 否则"这次为什么超预算"无法复现——同样的输入，改配置前后会得到不同结论，而审计要的是
 * "当时用的哪套标准"。所以 {@code claim()} 那一刻把配置折算成本记录写进
 * {@code agent_run.budget_json}，整个 Run 生命周期内只读它。</p>
 *
 * <p><b>为什么金额是 {@code milliPoints} 而不是货币 {@code BigDecimal}：</b>
 * 这个系统里"钱"的单位是**毫分**（milli-point，{@code long}），由
 * {@code BillingService} 按 token 与模型单价折算，落在 {@code points_ledger.amount}。
 * 再引入一个 BigDecimal 货币字段就是第二套单位体系，两套之间的换算迟早会漂移，
 * 而"预算"与"账单"对不上时没人能判断是哪个错了。所以这里复用同一套单位。</p>
 *
 * <p><b>0 与负数一律解释为"不限制"</b>，与 {@code AgentConversationProperties} 里
 * {@code maxTotalTokens}/{@code maxMilliPoints}/{@code maxActiveMinutes} 的既有约定一致。
 * 这个约定集中在 {@link #isLimited(long)}，不要在各处重复判断：把 0 当成"上限为 0"是个安静的
 * 陷阱——配置里留空或写 0 是"不限制"的常见写法，一旦按"上限 0"解释，工具闸门会把每一次调用
 * 都挡下来，而现象只是"智能体什么也不做"。{@code maxSteps} 是唯一例外，它由
 * {@code RunBudgetGuard.budgetFrom} 保证至少为 1（不设上界就是一个没有出口的循环）。</p>
 *
 * @param maxSteps          模型决策次数上限（含 resume 后继续累计）
 * @param maxToolCalls      工具调用总数上限，与 {@code maxSteps} 正交
 * @param maxInputTokens    累计输入 token 上限
 * @param maxOutputTokens   累计输出 token 上限
 * @param maxTotalTokens    累计输入+输出 token 上限
 * @param maxMilliPoints    累计消耗毫分上限
 * @param maxActiveDuration 累计**活跃**时长上限；只累加 RUNNING 期间，等待审批/暂停期间不累加
 */
public record RunBudget(
        int maxSteps,
        int maxToolCalls,
        long maxInputTokens,
        long maxOutputTokens,
        long maxTotalTokens,
        long maxMilliPoints,
        Duration maxActiveDuration) {

    /** 不限制该维度时的取值。用负数而不是 0，是为了让"没配置"和"配成 0"分开。 */
    public static final int UNLIMITED = -1;

    /** 各维度都不限制。给关闭执行日志的兼容路径与单元测试用，生产路径不应出现。 */
    public static RunBudget unlimited() {
        return new RunBudget(UNLIMITED, UNLIMITED, UNLIMITED, UNLIMITED, UNLIMITED, UNLIMITED, null);
    }

    public RunBudget {
        // 时长为 0 或负一律视为不限制：Duration.ZERO 作为"上限"会让 Run 一步都跑不了，
        // 而它最可能的来源是配置缺省，不是有人真的想禁止执行。
        if (maxActiveDuration != null && (maxActiveDuration.isZero() || maxActiveDuration.isNegative())) {
            maxActiveDuration = null;
        }
    }

    /** 该维度是否设了上限。**0 与负数都不限制**，所以判据是 {@code > 0} 而不是 {@code >= 0}。 */
    public static boolean isLimited(long limit) {
        return limit > 0;
    }

    public boolean limitsSteps() {
        return isLimited(maxSteps);
    }

    public boolean limitsToolCalls() {
        return isLimited(maxToolCalls);
    }

    public boolean limitsInputTokens() {
        return isLimited(maxInputTokens);
    }

    public boolean limitsOutputTokens() {
        return isLimited(maxOutputTokens);
    }

    public boolean limitsTotalTokens() {
        return isLimited(maxTotalTokens);
    }

    public boolean limitsMilliPoints() {
        return isLimited(maxMilliPoints);
    }

    public boolean limitsActiveDuration() {
        return maxActiveDuration != null;
    }

    /** 是否完全没有上限。用于跳过整轮判定，避免无谓的聚合查询。 */
    public boolean isUnlimited() {
        return !limitsSteps() && !limitsToolCalls() && !limitsInputTokens()
                && !limitsOutputTokens() && !limitsTotalTokens() && !limitsMilliPoints()
                && !limitsActiveDuration();
    }
}
