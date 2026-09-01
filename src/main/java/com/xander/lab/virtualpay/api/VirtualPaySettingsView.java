package com.xander.lab.virtualpay.api;

import java.time.LocalDateTime;

/** 管理台虚拟支付配置视图；AppKey 永不回传。 */
public record VirtualPaySettingsView(
        boolean enabled,
        String offerId,
        boolean appKeyConfigured,
        Product plus,
        Product pro,
        Product ultra,
        LocalDateTime updatedAt) {

    /** 固定等级商品的非敏感配置。 */
    public record Product(String productId, int priceCents, int durationDays) {
    }
}
