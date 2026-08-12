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
    /** TCP connect timeout in milliseconds. */
    private int connectTimeoutMillis = 15_000;
    /** Maximum time waiting for streamed model data in milliseconds. */
    private int readTimeoutMillis = 300_000;
}
