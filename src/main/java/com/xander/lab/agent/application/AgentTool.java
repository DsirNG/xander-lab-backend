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

    /**
     * 带会话上下文的执行入口，编排循环统一走这里。
     *
     * <p>默认转发到只带 userId 的重载，所以既有工具无需改动；
     * 需要写回会话状态的工具（任务计划等）覆写本方法。</p>
     */
    default JsonNode execute(AgentToolContext context, JsonNode args, ProgressListener onProgress) {
        return execute(context.userId(), args, onProgress);
    }

    /** Receives human-readable progress hints during a long-running tool call. */
    interface ProgressListener {
        void onProgress(String message);

        /** Streams tool-owned draft/output text without pretending it is the agent's final answer. */
        default void onToolDelta(String delta) {
        }

        /** Lets a long-running tool cooperatively stop between remote stages. */
        default boolean isCancellationRequested() {
            return false;
        }

        /** Throws at cooperative cancellation checkpoints. */
        default void checkCancellation() {
            if (isCancellationRequested()) {
                throw new java.util.concurrent.CancellationException("用户已停止当前智能体任务");
            }
        }
    }
}
