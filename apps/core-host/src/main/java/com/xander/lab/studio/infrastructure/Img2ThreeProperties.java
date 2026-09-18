package com.xander.lab.studio.infrastructure;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@ToString(exclude = "agent")
@Component
@ConfigurationProperties(prefix = "img2three")
public class Img2ThreeProperties {
    /** Enable the checked-in img2threejs staged pipeline instead of the legacy one-shot scene spec. */
    private boolean pipelineEnabled = true;
    /** Path to the img2threejs checkout, relative to the application working directory when not absolute. */
    private String pipelineRoot = "plugins/img2threejs";
    /**
     * Python 3 executable used by img2threejs' dependency-free forge scripts.
     * Blank means auto-detect a working Python 3 command for the current operating system.
     */
    private String pythonCommand = "";
    private Agent agent = new Agent();

    /** Keep the existing model-client API while binding credentials below img2three.agent. */
    public boolean isAgentEnabled() {
        return agent != null && agent.isEnabled();
    }

    public String getBaseUrl() {
        return agent == null ? "" : agent.getBaseUrl();
    }

    public String getApiKey() {
        return agent == null ? "" : agent.getApiKey();
    }

    public String getModel() {
        return agent == null ? "" : agent.getModel();
    }

    @Data
    @ToString(exclude = "apiKey")
    public static class Agent {
        /** Disabled by default so deterministic local reconstruction remains the safe baseline. */
        private boolean enabled = false;
        /**
         * OpenAI-compatible Responses API base URL.
         * The blog.agent fallback is a **config-level** placeholder in application.yml
         * ({@code img2three.agent.base-url} → {@code blog.agent.base-url}), not a runtime
         * read of another domain's bean.
         */
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "";
    }
}
