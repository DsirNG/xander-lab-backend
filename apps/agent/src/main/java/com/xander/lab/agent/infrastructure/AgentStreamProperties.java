package com.xander.lab.agent.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** V9 §95/§96 的 Agent 长连接切换开关。 */
@Component
@ConfigurationProperties(prefix = "agent.stream")
public class AgentStreamProperties {

    /** 拒绝新建 SSE / WebSocket；已建立连接仍由自身或安全平面收口。 */
    private boolean drainEnabled;

    public boolean isDrainEnabled() {
        return drainEnabled;
    }

    public void setDrainEnabled(boolean drainEnabled) {
        this.drainEnabled = drainEnabled;
    }
}
