package com.xander.lab.agent.infrastructure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Agent 事件总线的跨实例传播配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.event-bus")
public class AgentEventBusProperties {

    /** 默认关闭；多实例 core-host 必须在所有实例上一致开启。 */
    private boolean pubsubEnabled = false;

    /** 多实例 Agent 事件频道。 */
    private String pubsubChannel = "dinqor:agent-events";
}
