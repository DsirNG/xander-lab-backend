package com.xander.lab.gateway;

/** 与 core-host 安全事件 Publisher 对齐的跨进程消息形状。 */
public record GatewaySecurityEventMessage(
        Long id,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payload) {
}
