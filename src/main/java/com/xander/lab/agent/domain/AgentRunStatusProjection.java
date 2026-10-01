package com.xander.lab.agent.domain;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * {@code agent_run.status} 到 {@code agent_conversation.status} 的投影。
 *
 * <p><b>为什么要有这一层。</b>两列说的是同一件事，但用途不同：</p>
 *
 * <ul>
 *   <li>{@code agent_run.status} 是**权威**——大写九态，记录这次执行到底发生了什么；</li>
 *   <li>{@code agent_conversation.status} 是**投影**——小写，是前端契约，同时充当
 *       {@code claim()} / 租约续期 / 取消轮询这些条件更新的守卫列。</li>
 * </ul>
 *
 * <p>以前两列各写各的：{@code claim()} 里手写 {@code "ready"}、{@code fail()} 里手写
 * {@code "failed"}、{@code recoverStale()} 里再写一次 {@code "failed"}。同一件事两个词、
 * 三处拼写，任何一次跨层复制粘贴都会静默失效——而失效只在某个前端分支上看得出来。
 * 所以映射收在这一个方法里，谁都不许再在会话上直接拼状态字符串。</p>
 *
 * <p><b>投影不是单射。</b>{@code COMPLETED} 与 {@code CANCELLED} 都投成 {@code ready}：
 * 对用户来说"做完了"和"我按了停止"是同一个后续动作——可以继续说话。给取消单开一个投影值
 * 会平白多一个前端分支，而它要表达的差别（这轮是正常收口还是被停止）已经在消息记录里了。</p>
 */
public final class AgentRunStatusProjection {
    /** 可以开始新一轮；终态与"已停止"都落在这里。 */
    public static final String READY = "ready";
    /** 正在执行。唯一会拒绝 {@code claim()} 的投影值。 */
    public static final String RUNNING = "running";
    /** 有调用在等属主决定，见 {@link AgentRun#STATUS_WAITING_APPROVAL}。 */
    public static final String AWAITING_APPROVAL = "awaiting_approval";
    /** 用户主动暂停，见 {@link AgentRun#STATUS_PAUSED}。 */
    public static final String PAUSED = "paused";
    /** 持有者进程消失，可继续对话接着做。 */
    public static final String INTERRUPTED = "interrupted";
    /** 预算耗尽，可继续对话让模型接着收口。 */
    public static final String BUDGET_EXHAUSTED = "budget_exhausted";
    /** 执行失败。与 interrupted 分开，前端才能区分"重试有用"和"重试也没用"。 */
    public static final String FAILED = "failed";

    private static final Set<String> ALL;

    /**
     * 拒绝 {@code claim()} 的两个投影值。
     *
     * <p>{@code running}：这一轮还在跑。</p>
     *
     * <p>{@code awaiting_approval}：有具体调用在等属主决定。开新意图会让那条待决审批与它的
     * invocation 永久悬空——没有任何扫描会收它（对账器只看 {@code RUNNING} 的 invocation，
     * 而它停在 {@code WAITING_APPROVAL}）。给"被顶掉的待决审批"一个去向是独立的一步，
     * 在那之前这里保持拒绝。</p>
     */
    private static final Set<String> UNCLAIMABLE = Set.of(RUNNING, AWAITING_APPROVAL);

    static {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        all.add(READY);
        all.add(RUNNING);
        all.add(AWAITING_APPROVAL);
        all.add(PAUSED);
        all.add(INTERRUPTED);
        all.add(BUDGET_EXHAUSTED);
        all.add(FAILED);
        ALL = Collections.unmodifiableSet(all);
    }

    private AgentRunStatusProjection() {
    }

    /**
     * 把权威状态投影成前端契约值。
     *
     * <p>遇到未定义的状态**直接抛**，不返回 {@code null} 也不兜底成 {@code ready}：
     * 兜底会让一个拼错的状态静默变成"可以继续"，而真正的后果是下一轮执行与上一轮的
     * 收口逻辑撞在一起。宁可在这里响亮地失败。</p>
     *
     * @param runStatus {@link AgentRun} 的九个状态之一
     * @throws IllegalArgumentException 状态未定义
     */
    public static String of(String runStatus) {
        if (AgentRun.STATUS_COMPLETED.equals(runStatus) || AgentRun.STATUS_CANCELLED.equals(runStatus)) {
            return READY;
        }
        if (AgentRun.STATUS_FAILED.equals(runStatus)) return FAILED;
        if (AgentRun.STATUS_WAITING_APPROVAL.equals(runStatus)) return AWAITING_APPROVAL;
        if (AgentRun.STATUS_PAUSED.equals(runStatus)) return PAUSED;
        if (AgentRun.STATUS_INTERRUPTED.equals(runStatus)) return INTERRUPTED;
        if (AgentRun.STATUS_BUDGET_EXHAUSTED.equals(runStatus)) return BUDGET_EXHAUSTED;
        if (AgentRun.STATUS_CREATED.equals(runStatus) || AgentRun.STATUS_RUNNING.equals(runStatus)) {
            return RUNNING;
        }
        throw new IllegalArgumentException("未定义的执行状态，无法投影：" + runStatus);
    }

    /**
     * 这个会话状态能不能被 {@code claim()} 开启新意图。
     *
     * <p>用**拒绝名单**（{@link #UNCLAIMABLE}）而不是允许名单。允许名单的漏洞是静默的：
     * 新增一个可恢复态却忘了加进白名单，用户就被锁在自己的会话外面，直到有人恰好打开一次
     * 那个会话才自愈——{@code recoverStale} 与 {@code AgentRunStaleReaper} 修的正是这一类。
     * 拒绝名单的方向则相反：漏掉一个状态只会让它在"执行中"被误判为可继续，而那会立刻
     * 在条件更新上撞出 0 行，看得见。</p>
     *
     * <p>调用方必须用 {@link #unclaimableStatuses()} 生成 SQL 条件，不要自己再列一遍——
     * 两处各写一份就是这个类想消灭的那种漂移。</p>
     */
    public static boolean canClaim(String conversationStatus) {
        return !UNCLAIMABLE.contains(conversationStatus);
    }

    /** {@code claim()} 的拒绝名单，供 SQL 的 {@code NOT IN} 直接使用。 */
    public static Set<String> unclaimableStatuses() {
        return UNCLAIMABLE;
    }

    /** 是否是已定义的投影值。 */
    public static boolean isKnown(String conversationStatus) {
        return ALL.contains(conversationStatus);
    }

    /** 全部投影值，按生命周期顺序。 */
    public static Set<String> allStatuses() {
        return ALL;
    }
}
