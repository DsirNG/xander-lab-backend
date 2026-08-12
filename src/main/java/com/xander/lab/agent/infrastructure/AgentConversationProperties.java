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
}