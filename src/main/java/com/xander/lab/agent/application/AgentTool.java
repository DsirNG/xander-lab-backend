package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A tool an orchestration agent may invoke. Tools are always executed with an
 * explicit user id; they must not rely on request thread context.
 */
public interface AgentTool {

    String name();

    /** For the model: what the tool does and when to use it. */
    String description();

    /** JSON Schema (as a JsonNode) of the tool arguments. */
    JsonNode parametersSchema();

    /**
     * Executes the tool.
     *
     * @param userId    owning user
     * @param args      validated JSON arguments
     * @param onProgress stage/progress hints to stream to the conversation
     * @return structured JSON result visible to the model
     */
    JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress);

    /** Receives human-readable progress hints during a long-running tool call. */
    interface ProgressListener {
        void onProgress(String message);
    }
}