package com.xander.lab.agent.infrastructure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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
    /** 构建模型上下文时保留的最近消息条数。 */
    private int contextMessageLimit = 30;
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
