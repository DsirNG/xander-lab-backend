package com.xander.lab.billing.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 保存模型定价请求体。单价单位：毫分 / 每 1K token（¥2/M = 200）。
 */
public record SaveModelPriceRequest(
        /** 输入 token 单价：毫分 / 每 1K token */
        @NotNull @Min(0) Long inputMilliPer1kToken,
        /** 输出 token 单价：毫分 / 每 1K token */
        @NotNull @Min(0) Long outputMilliPer1kToken,
        /** 缓存读取 token 单价：毫分 / 每 1K token，缺省 0 */
        @Min(0) Long cachedMilliPer1kToken,
        /** 缓存写入 token 单价：毫分 / 每 1K token，缺省 0（当前 usage 无该计量，预留） */
        @Min(0) Long cachedWriteMilliPer1kToken,
        /** 是否启用计费；false 时回退 yml 默认价 */
        Boolean enabled,
        /** 备注：价目来源、生效说明等 */
        String remark) {

    public SaveModelPriceRequest {
        if (cachedMilliPer1kToken == null) cachedMilliPer1kToken = 0L;
        if (cachedWriteMilliPer1kToken == null) cachedWriteMilliPer1kToken = 0L;
        if (enabled == null) enabled = true;
    }
}
