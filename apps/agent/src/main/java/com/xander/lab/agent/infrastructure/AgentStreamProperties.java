package com.xander.lab.agent.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** V9 §95/§96 的 Agent 长连接切换开关。 */
@Component
@ConfigurationProperties(prefix = "agent.stream")
public class AgentStreamProperties {

    /** 拒绝新建 SSE / WebSocket；已建立连接仍由自身或安全平面收口。 */
    private boolean drainEnabled;

    /** 逐实例指标与 deadline 日志使用的稳定标签。 */
    private String instanceId = "unknown";

    /** Unix epoch milliseconds at which the instance force-closes remaining streams. */
    private long drainDeadlineAt;

    public boolean isDrainEnabled() {
        return drainEnabled;
    }

    public void setDrainEnabled(boolean drainEnabled) {
        this.drainEnabled = drainEnabled;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public long getDrainDeadlineAt() {
        return drainDeadlineAt;
    }

    public void setDrainDeadlineAt(long drainDeadlineAt) {
        this.drainDeadlineAt = drainDeadlineAt;
    }
}
