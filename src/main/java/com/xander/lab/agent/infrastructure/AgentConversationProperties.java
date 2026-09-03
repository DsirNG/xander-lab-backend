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
     * 单轮允许的自检驳回次数。
     *
     * <p>反思要能纠偏，也要能停：预算用尽后即使自检仍不满意也放行，
     * 由回复如实交代未完成部分，避免和模型互相纠缠把步数耗光。</p>
     */
    private int maxReflections = 2;
    /**
     * 单个子智能体（delegate_subtask）允许的最大推理步数。
     *
     * <p>刻意比主循环小得多：派发的意义是把脏活关在隔离上下文里，
     * 不是再开一个能无限跑的循环。预算不够就如实交回中途观察，由主智能体决定是否再派一次。</p>
     */
    private int maxSubAgentSteps = 6;
}
