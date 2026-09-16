package com.xander.lab.core.api;

/**
 * 模型网关启用时功能缺少可用配置的运行时异常。
 *
 * <p>当 {@code ADMIN_MODEL_GATEWAY_ENABLED=true} 后，功能的使用完全由
 * 「功能模型配置」驱动：没有启用的配置行、或主/兜底供应商均不可用（停用、
 * 缺失或密钥解密失败）时抛出本异常，各模型客户端不得回退到环境变量。
 * 由 {@code GlobalExceptionHandler} 映射为 HTTP 400。</p>
 *
 * <p>本异常随 {@link ModelGateway} 一起位于契约模块，而不是留在 admin 域：
 * 它是该 SPI 的<b>约定行为</b>（「网关开着但这个功能没有可用端点」），
 * 跨部署单元的调用方（如 blogagent 的图片客户端）需要捕获它，
 * 全局异常处理器也要按类型映射。留在 admin 域只会白白多留一条跨单元 import。</p>
 */
public class FeatureModelNotConfiguredException extends RuntimeException {

    public FeatureModelNotConfiguredException(String featureKey) {
        super("功能「" + featureKey + "」未配置可用模型，请先在管理台「功能模型配置」中配置"
                + "（需要模型供应商已启用且密钥有效）");
    }
}
