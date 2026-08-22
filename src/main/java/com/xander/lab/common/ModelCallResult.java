package com.xander.lab.common;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 大模型调用结果：解析后的业务 JSON、本次调用实际消耗的 token 用量与命中的模型名。
 *
 * <p>模型客户端将原始响应拆分为「正文 JSON」与「usage」两部分返回，业务层
 * 在成功落库时据此向 billing 模块计费。</p>
 */
public record ModelCallResult(JsonNode payload, ModelUsage usage, String model) {

    public static ModelCallResult of(JsonNode payload, ModelUsage usage) {
        return new ModelCallResult(payload, usage, null);
    }

    /** Records the endpoint model that produced this usage so billing can select the exact price. */
    public static ModelCallResult of(JsonNode payload, ModelUsage usage, String model) {
        return new ModelCallResult(payload, usage, model);
    }

    /** 网关未返回 usage 时使用的一口价占位结果。 */
    public static ModelCallResult flat(JsonNode payload) {
        return new ModelCallResult(payload, ModelUsage.NONE, null);
    }
}
