package com.xander.lab.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "img2three.agent")
public class Img2ThreeProperties {
    /** OpenAI-compatible Responses API base URL. Falls back to blog.agent when blank at runtime. */
    private String baseUrl = "";
    private String apiKey = "";
    private String model = "";
}
