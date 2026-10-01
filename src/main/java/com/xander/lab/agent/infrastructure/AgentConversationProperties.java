package com.xander.lab.agent.infrastructure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 会话内存空间（上下文窗口 + 步数预算）的运行期配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.conversation")
public class AgentConversationProperties {
    /** 单轮对话允许的最大推理步数（思考+工具调用循环次数）。 */
    private int maxSteps = 20;
    /** 同一工具连续失败多少次后不再重试并结束本轮。 */
    private int maxToolFailures = 2;
/** 会话执行中断超过该分钟数后视为中断，允许重试。 */
    private int staleConversationMinutes = 30;
    /**
     * 构建模型上下文时保留的最近消息条数。
     *
     * <p>它现在只是一道兜底闸门，不再是决定"模型记得什么"的那个机制——那件事归压缩。
     * 早先这个值是 30，且没有压缩：第 31 条一到，前面的消息就无声无息地没了，
     * 而 30 条消息又几乎不可能撑到 token 阈值，于是压缩即使写好了也永远不会触发。
     * 抬高它是压缩能真正起作用的前提；超出这个条数时压缩会先把头部换成检查点，
     * 所以这道闸门在正常路径上并不会真的丢东西。</p>
     */
    private int contextMessageLimit = 120;
    /** 上下文压缩总开关；关掉即退回"只有窗口、超出即丢"的旧行为。 */
    private boolean compactionEnabled = true;
    /**
     * 模型上下文窗口的 token 数，压缩阈值按它的比例计算。
     *
     * <p>模型服务是管理员配置的第三方端点，我们拿不到它的真实窗口，只能由部署方声明。
     * 默认按 128k 这一档取值；换成更小窗口的模型时必须调小它，否则压缩会来得太晚。</p>
     */
    private int contextWindowTokens = 128000;
    /** 估算压力达到窗口这个比例即触发压缩，留出的余量用来容纳指令、工具表与本步输出。 */
    private double compactionThresholdRatio = 0.8D;
    /** 压缩时尾部原样保留的窗口比例；剩下的头部才会被换成检查点。 */
    private double compactionRetainRatio = 0.16D;
    /**
     * 单个子智能体（delegate_subtask）允许的最大推理步数。
     *
     * <p>刻意比主循环小得多：派发的意义是把脏活关在隔离上下文里，
     * 不是再开一个能无限跑的循环。预算不够就如实交回中途观察，由主智能体决定是否再派一次。</p>
     */
    private int maxSubAgentSteps = 6;
    /**
     * 一步里最多执行几个工具调用。
     *
     * <p>模型可以在一次决策里报出多个互不依赖的调用，服务端一步全做完，省掉来回的步数。
     * 但上限必须有：这是**资源闸门**，不是协议限制。模型理论上能一次报二十个查询，
     * 那样一步就把 {@link #maxSteps} 的预算一次性烧掉，还会把工具表推得很长。
     * 超出的调用不会被静默丢弃——编排循环会把"未执行"逐条写回历史，模型下一步可以重新发起。</p>
     */
    private int maxParallelTools = 4;
    /**
     * 单轮允许的工具调用**总数**上限，与 {@link #maxSteps} 正交。
     *
     * <p>为什么必须有它：{@code maxSteps} 只数"模型决策次数"，而一步可带
     * {@link #maxParallelTools} 个工具，于是单轮理论上限是 {@code 20 × 4 = 80} 次调用。
     * 只卡步数等于把"实际调了多少次"交给模型决定——它只要每步都塞满 4 个调用，
     * 就能在 20 步内把 80 次外部调用打出去。</p>
     *
     * <p>默认 60 **小于**那个理论上限，所以它只会收紧一个本来就有界的 Run，
     * 不会让任何正常轮次失败；而 60 次调用已经远超任何一个正当任务的需要。</p>
     */
    private int maxToolCalls = 60;
    /**
     * 单轮累计 token 上限（输入+输出，不含缓存命中部分）。**0 表示不限制。**
     *
     * <p>默认不开：token 与价格的对应关系是部署方才知道的事，给一个内置数字会让
     * 长任务在某个和模型窗口无关的地方莫名中断。要开就在部署时显式配。</p>
     */
    private long maxTotalTokens = 0L;
    /**
     * 单轮累计消耗上限（毫分，与积分账单同一单位）。**0 表示不限制。**
     *
     * <p>与 {@link #maxTotalTokens} 不是一回事：token 与金额只在**同一个模型**下才单调对应，
     * 换一个单价差十倍的模型，token 少一半反而更贵。真要控成本就得看金额。</p>
     */
    private long maxMilliPoints = 0L;
    /**
     * 单轮**活跃**时长上限（分钟）。**0 表示不限制。**
     *
     * <p><b>只累加 {@code RUNNING} 期间</b>，等待审批、暂停、中断期间不累加。这不是实现细节，
     * 而是这个字段能不能用的前提：若按墙钟算，一次 10:00 发起、10:01 转待审批、用户 13:00
     * 才批准的 Run，恢复瞬间就已经"运行了 3 小时"，直接判超预算——可它实际只跑了一分钟。</p>
     *
     * <p>如果以后还需要限制"一个任务不能挂三天"（含等待时间），**另加一个
     * {@code maxWallClockMinutes}**，不要把两种语义混进同一个字段。</p>
     */
    private int maxActiveMinutes = 0;
}
