package com.xander.lab.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Durable execution record for one claimed conversation generation.
 *
 * <p><b>这里的状态是权威。</b>{@code agent_conversation.status} 是它的投影（小写，供前端与
 * {@code claim()} 用），映射集中在 {@link AgentRunStatusProjection}。两边不许各写各的：
 * 任何一次"在会话上直接拼一个状态字符串"的改动，都会让权威列和投影列漂移，而漂移只在
 * 某个前端分支上才看得出来。</p>
 *
 * <p>分类由**不变量**推导，不是按需要倒推出来的：</p>
 *
 * <ul>
 *   <li><b>进行中</b>：可继续迁移。</li>
 *   <li><b>可恢复态</b>：可以 {@code resume()} 回到 {@code RUNNING}。其中三个
 *       （{@code PAUSED} / {@code INTERRUPTED} / {@code BUDGET_EXHAUSTED}）同时允许
 *       {@code claim()} 开启新意图；{@code WAITING_APPROVAL} 是例外——它有一条待决审批，
 *       开新意图会让那条审批与它的 invocation 永久悬空。**"可恢复"不等于"可 claim"**，
 *       两件事分开看。</li>
 *   <li><b>终态</b>：{@code terminal → 不再迁移}。只允许后续 {@code claim()} 开一个**新的** Run。</li>
 * </ul>
 *
 * <p>之所以不能有"可恢复的终态"：Resume 要求 {@code INTERRUPTED → RUNNING}，
 * 而一个还能迁移的状态按定义就不是终态。当前 Run 与 Attempt 还是同一个概念，
 * 不要为了迁就"一次 Run 多次尝试"的叙事去放宽终态的含义——那件事该等
 * {@code agent_run_attempt} 出现时再说。</p>
 */
@Data
@TableName("agent_run")
public class AgentRun {
    /** 已落库但尚未开始执行。**尚无生产者**：{@code startRun()} 直接写 RUNNING。 */
    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_RUNNING = "RUNNING";
    /**
     * 某个已落库的工具调用在等它的属主做决定。
     *
     * <p>与 {@link #STATUS_PAUSED} 的区别是**在等什么**：这里等的是对**某一个具体调用**的
     * 批准/拒绝（{@code agent_approval} 里有一行 PENDING 与它对应）；PAUSED 等的是
     * **用户的下一个动作**，没有任何待决的调用。两者都暂停，但恢复时的动作完全不同
     * （前者要先执行那条已批准的调用），混用会让审批恢复去执行一条根本没被批准的调用。</p>
     */
    public static final String STATUS_WAITING_APPROVAL = "WAITING_APPROVAL";
    /** 用户主动暂停。**尚无生产者**：目前没有"暂停"入口，只有取消。 */
    public static final String STATUS_PAUSED = "PAUSED";
    /** 持有者进程消失（崩溃、被 kill），执行未收口。由 {@code AgentRunStaleReaper} 写入。 */
    public static final String STATUS_INTERRUPTED = "INTERRUPTED";
    /** 预算耗尽（当前只有步数一种）。可继续对话让模型接着收口。 */
    public static final String STATUS_BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    /** 用户请求停止并已收口。 */
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_FAILED = "FAILED";

    private static final Set<String> IN_PROGRESS = Set.of(STATUS_CREATED, STATUS_RUNNING);
    private static final Set<String> RESUMABLE = Set.of(
            STATUS_WAITING_APPROVAL, STATUS_PAUSED, STATUS_INTERRUPTED, STATUS_BUDGET_EXHAUSTED);
    private static final Set<String> TERMINAL = Set.of(
            STATUS_COMPLETED, STATUS_CANCELLED, STATUS_FAILED);

    /**
     * 全部状态，按生命周期顺序。
     *
     * <p>顺序有意义：契约测试与迁移脚本的枚举说明都按它走，所以用 {@link LinkedHashSet}
     * 而不是 {@code Set.of}（后者没有稳定迭代顺序）。</p>
     */
    private static final Set<String> ALL;

    /**
     * 可恢复态，但按 {@link #ALL} 的生命周期顺序排列。
     *
     * <p>顺序从 {@code ALL} 派生而不是再手写一遍：枚举说明与拒绝消息都按它输出，
     * 手写一份就会在新增状态时与 {@link #RESUMABLE} 分叉——而分叉的表现是"消息里少一个状态"，
     * 没人会注意到。</p>
     */
    private static final Set<String> RESUMABLE_ORDERED;

    static {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        all.add(STATUS_CREATED);
        all.add(STATUS_RUNNING);
        all.add(STATUS_WAITING_APPROVAL);
        all.add(STATUS_PAUSED);
        all.add(STATUS_INTERRUPTED);
        all.add(STATUS_BUDGET_EXHAUSTED);
        all.add(STATUS_COMPLETED);
        all.add(STATUS_CANCELLED);
        all.add(STATUS_FAILED);
        ALL = java.util.Collections.unmodifiableSet(all);
        LinkedHashSet<String> resumable = new LinkedHashSet<>();
        for (String status : all) {
            if (RESUMABLE.contains(status)) resumable.add(status);
        }
        RESUMABLE_ORDERED = java.util.Collections.unmodifiableSet(resumable);
    }

    /** 是否正在执行。只有这两个状态会拒绝 {@code claim()}。 */
    public static boolean isInProgress(String status) {
        return IN_PROGRESS.contains(status);
    }

    /** 是否可以由 {@code resume()} 延续同一意图。 */
    public static boolean isResumable(String status) {
        return RESUMABLE.contains(status);
    }

    /** 是否终态。终态不允许再迁移，只允许后续 claim 开新 Run。 */
    public static boolean isTerminal(String status) {
        return TERMINAL.contains(status);
    }

    /** 是否是已定义的状态。未定义的状态必须显式失败，不能落进任何一类。 */
    public static boolean isKnown(String status) {
        return ALL.contains(status);
    }

    /** 全部状态，按生命周期顺序。 */
    public static Set<String> allStatuses() {
        return ALL;
    }

    /**
     * 全部**可恢复态**，按生命周期顺序。
     *
     * <p>给"为什么这次不能恢复"的拒绝消息用：把允许的集合直接列出来，调用方不用去翻代码，
     * 也不会漏掉某个状态。</p>
     */
    public static Set<String> resumableStatuses() {
        return RESUMABLE_ORDERED;
    }

    @TableId(type = IdType.AUTO)
    private Long id;
    /** Owning user; duplicated from the conversation for journal authorization queries. */
    private Long userId;
    private Long conversationId;
    /** Immutable conversation execution generation, unique within one conversation. */
    private Long runVersion;
    /**
     * 用户意图周期标识：一次用户诉求一个周期，**与 {@link #runVersion} 正交**。
     *
     * <p>{@code claim()} 铸新的（"这是一件新的事"），{@code resume()} 复用旧的
     * （"还是那件事，只是刚才断了"）。这个区别就是幂等采纳的判据：只有同一周期内的
     * 重复调用才算"重试"，跨周期的一定是新动作——否则用户真的要发第二条提醒时，
     * 系统会把它当成重试而吞掉。</p>
     *
     * <p>刻意**不**参与 {@code run_version} 的递增：代次回答"谁有权写"，周期回答"这是不是同一件事"，
     * 合成一个字段后两边的语义都会退化。存量数据为 {@code null}（无法回溯），读侧必须容忍。</p>
     */
    private String intentEpoch;
    /** 九个状态之一，见本类常量；分类与迁移合法性见 {@link AgentRunStatusProjection}。 */
    private String status;
    /** 当轮技能路由**机制**快照：写的是"怎么决定的"而不是"决定了什么"，因此不含用户提示词。 */
    private String intentJson;
    /** 当轮技能供给快照：目录里提供了哪些技能，以及当时的工具表。 */
    private String skillsJson;
    /** 当轮技能供给的规模摘要（目录条数、工具数）。**不是**预算快照——预算是 {@link #budgetJson}。 */
    private String executionSnapshotJson;
    /**
     * 本轮冻结的 {@link RunBudget} JSON。
     *
     * <p>{@code claim()} 那一刻由配置折算写入，此后整个 Run 只读它：运行期改配置不该让一个
     * 跑了一半的 Run 突然换判定标准，否则"这次为什么超预算"无法复现。</p>
     */
    private String budgetJson;
    /**
     * 累计**活跃**时长（毫秒），只含 RUNNING 期间。
     *
     * <p>等待审批、暂停、中断的时间不计入。按墙钟算的话，一次 10:00 发起、10:01 转待审批、
     * 用户 13:00 才批准的 Run，在恢复瞬间就已经"运行了 3 小时"——可它实际只跑了一分钟。</p>
     */
    private Long activeDurationMs;
    /**
     * 最近一次进入 RUNNING 的时刻；**非 NULL 表示此刻正在 RUNNING**。
     *
     * <p>离开 RUNNING 时用它结算一段活跃时长再置 NULL。崩溃路径没有"离开"事件，
     * 所以由 {@code AgentRunStaleReaper} 用会话租约的 {@code updated_at} 作为
     * "最后已知存活时刻"来结算——那是续期任务实际触碰过的时钟，比 {@code agent_run.updated_at}
     * 可靠（后者只在状态迁移时更新，一个健康的长任务在它看来是"很久没动"）。</p>
     */
    private LocalDateTime lastRunningAt;
    /**
     * 最近一次被调度器**自动** resume 的时间；非 {@code null} 表示"这个 Run 已经自动恢复过一次"。
     *
     * <p>它是"最多自动 resume 一次"这条安全阀的落点。没有它的话，一个必然失败的 Run
     * （比如依赖的工具坏了）会被调度器每个周期捞起来重跑一次，永远转下去——
     * 而这正好是最需要被限制的那类故障。非空之后调度器只标记、不再自动恢复，
     * 把决定权交回用户。</p>
     */
    private LocalDateTime autoResumedAt;
    private String failureReason;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
