package com.xander.lab.agent.domain;

import java.time.Duration;

/**
 * 一次 Run 到当前为止**已消耗**的资源，与 {@link RunBudget} 一一对应。
 *
 * <p>它是聚合结果而不是一张表：每个字段都从既有事实日志现算（{@code agent_step} 的步数与 token、
 * {@code agent_tool_invocation} 的调用条数、{@code agent_run} 的活跃时长）。**不落
 * {@code used_*} 冗余列**——冗余列必然与事实源漂移，于是要额外维护"计数与事实源不一致"的对账，
 * 而 {@code maxSteps} 只有 20，即便每步聚合一次也只有 {@code 1+2+…+20 = 210} 次索引范围扫描。
 * 为了省这点读引入第二套真相不划算。</p>
 *
 * <p>{@code milliPoints} 是**估算**而不是已扣金额：真实扣费发生在轮次结束的成功哨点上，
 * 且带幂等键。预算判定要的是"照这个势头下去会不会超"，不是"账上扣了多少"，
 * 所以这里用同一个价格函数现算，两者口径一致但用途不同。</p>
 *
 * @param steps          已完成的模型决策步数
 * @param toolCalls      已落库的工具调用条数
 * @param inputTokens    累计输入 token
 * @param outputTokens   累计输出 token
 * @param cachedTokens   累计命中缓存的输入 token（是 {@code inputTokens} 的子集，不重复计入总量）
 * @param milliPoints    按当前模型单价估算的累计消耗毫分
 * @param activeDuration 累计活跃时长，只含 RUNNING 期间
 */
public record RunUsage(
        int steps,
        long toolCalls,
        long inputTokens,
        long outputTokens,
        long cachedTokens,
        long milliPoints,
        Duration activeDuration) {

    public static final RunUsage EMPTY =
            new RunUsage(0, 0L, 0L, 0L, 0L, 0L, Duration.ZERO);

    /**
     * 输入+输出。**不含 cached**：缓存命中的输入 token 已经算在 {@code inputTokens} 里，
     * 再加一次会让同一批 token 被计两遍，于是 token 预算比实际早一半触发。
     */
    public long totalTokens() {
        return inputTokens + outputTokens;
    }

    /** 只改步数、其余照旧。循环每前进一步用它推进"已用步数"，避免整对象重建时漏字段。 */
    public RunUsage withSteps(int usedSteps) {
        return new RunUsage(usedSteps, toolCalls, inputTokens, outputTokens, cachedTokens,
                milliPoints, activeDuration);
    }

    /** 只改工具调用数。检查点 2 的预扣用它。 */
    public RunUsage withToolCalls(long usedToolCalls) {
        return new RunUsage(steps, usedToolCalls, inputTokens, outputTokens, cachedTokens,
                milliPoints, activeDuration);
    }
}
