package com.xander.lab.common;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一次大模型调用返回的 token 用量。
 *
 * <p>数值取自 OpenAI Responses 兼容接口的 {@code usage} 对象；当网关未返回
 * usage 时使用全零实例，调用方据此退回一口价计价。</p>
 */
public record ModelUsage(long inputTokens, long cachedTokens, long outputTokens) {

    public static final ModelUsage NONE = new ModelUsage(0, 0, 0);

    public boolean isEmpty() {
        return inputTokens <= 0 && cachedTokens <= 0 && outputTokens <= 0;
    }

    /** 从标准 Responses API 报文的 usage 节点提取用量，缺字段按 0 处理。 */
    public static ModelUsage fromJson(JsonNode usage) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) {
            return NONE;
        }
        long input = usage.path("input_tokens").asLong(0);
        long cached = usage.path("input_tokens_details").path("cached_tokens").asLong(0);
        long output = usage.path("output_tokens").asLong(0);
        return new ModelUsage(Math.max(0, input), Math.max(0, cached), Math.max(0, output));
    }
}