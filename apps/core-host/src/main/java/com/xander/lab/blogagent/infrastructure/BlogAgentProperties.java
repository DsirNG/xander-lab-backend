package com.xander.lab.blogagent.infrastructure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "blog.agent")
public class BlogAgentProperties {
    /** OpenAI-compatible Responses API base URL. */
    private String baseUrl = "https://api.openai.com/v1";
    private String apiKey;
    private String model;
    private boolean webSearchEnabled = true;
    /** TCP connect timeout for model requests in milliseconds. */
    private int connectTimeoutMillis = 15_000;
    /** Maximum wait for model response data in milliseconds. */
    private int readTimeoutMillis = 300_000;
    /** Optional OpenAI-compatible image generation model. Blank disables illustrations. */
    private String imageModel;
    private String imageSize = "1536x1024";
    private int maxIllustrations = 3;
    /** Running tasks older than this are treated as interrupted workers. */
    private int staleTaskMinutes = 30;
}
