package com.xander.lab.agent.infrastructure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Configuration of the orchestration model gateway owned by the agent module. */
@Data
@Component
@ConfigurationProperties(prefix = "agent.model")
public class AgentModelProperties {
    private String baseUrl = "https://api.openai.com/v1";
    private String apiKey;
    private String model;
    private boolean webSearchEnabled = true;
    /** Reasoning effort for GPT-5/o-series models; blank disables the parameter. */
    private String reasoningEffort = "low";
    /** TCP connect timeout in milliseconds. */
    private int connectTimeoutMillis = 15_000;
    /**
     * Streaming read timeout in milliseconds: the longest silence we tolerate between
     * two bytes from one endpoint before abandoning it.
     *
     * <p>这是"两次收到数据之间"的上限，不是整轮上限——流式响应每吐一个字节就重置它。
     * 所以调小它不会误杀正常的长回答，只会切掉真正卡死的端点。生产实测有的上游会
     * 挂着不出任何字节，直到网关层在 25 秒后才吐 524；把默认值收到 60 秒以内，
     * 我们能比网关更早放弃这个端点、转投下一个候选，而不是干等。
     */
    private int readTimeoutMillis = 60_000;
}
