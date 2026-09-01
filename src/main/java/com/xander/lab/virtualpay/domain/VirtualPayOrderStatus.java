package com.xander.lab.virtualpay.domain;

/** 虚拟支付订单状态；只有 DELIVERED 表示订阅权益已可靠发放。 */
public enum VirtualPayOrderStatus {
    PENDING,
    DELIVERED,
    REFUNDED,
    FAILED
}
