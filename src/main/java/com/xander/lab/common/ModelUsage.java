package com.xander.lab.common;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一次大模型调用返回的 token 用量。
 *
 * <p>数值取自 OpenAI 兼容接口的 {@code usage} 对象，两种协议都认：Responses 用
 * {@code input_tokens}/{@code output_tokens}，Chat Completions 用
 * {@code prompt_tokens}/{@code completion_tokens}。当网关未返回 usage 时使用全零
 * 实例，调用方据此退回一口价计价。</p>
 */
public record ModelUsage(long inputTokens, long cachedTokens, long outputTokens) {

    public static final ModelUsage NONE = new ModelUsage(0, 0, 0);

    public boolean isEmpty() {
        return inputTokens <= 0 && cachedTokens <= 0 && outputTokens <= 0;
    }

    /** 从 OpenAI 兼容报文的 usage 节点提取用量，缺字段按 0 处理。 */
    public static ModelUsage fromJson(JsonNode usage) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) {
            return NONE;
        }
        // Responses 叫 input_tokens/output_tokens，Chat Completions 叫
        // prompt_tokens/completion_tokens。只认前者时，走 Chat Completions 的
        // 调用会静默记成全零，用量统计和计费就都平白丢了。
        long input = usage.path("input_tokens").asLong(0);
        if (input <= 0) input = usage.path("prompt_tokens").asLong(0);
        long cached = usage.path("input_tokens_details").path("cached_tokens").asLong(0);
        if (cached <= 0) {
            cached = usage.path("prompt_tokens_details").path("cached_tokens").asLong(0);
        }
        long output = usage.path("output_tokens").asLong(0);
        if (output <= 0) output = usage.path("completion_tokens").asLong(0);
        return new ModelUsage(Math.max(0, input), Math.max(0, cached), Math.max(0, output));
    }
}