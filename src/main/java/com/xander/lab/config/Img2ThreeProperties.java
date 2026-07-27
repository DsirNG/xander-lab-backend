package com.xander.lab.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "img2three.agent")
public class Img2ThreeProperties {
    /** Enable the checked-in img2threejs staged pipeline instead of the legacy one-shot scene spec. */
    private boolean pipelineEnabled = true;
    /** Path to the img2threejs checkout, relative to the application working directory when not absolute. */
    private String pipelineRoot = "plugins/img2threejs";
    /** Python 3 executable used by img2threejs' dependency-free forge scripts. */
    private String pythonCommand = "python";
    /** OpenAI-compatible Responses API base URL. Falls back to blog.agent when blank at runtime. */
    private String baseUrl = "";
    private String apiKey = "";
    private String model = "";
}
