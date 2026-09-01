package com.xander.lab.admin.api;

/** 管理员保存个人主体虚拟支付配置；AppKey 留空表示保留已有密文。 */
public record SaveVirtualPaySettingsRequest(
        boolean enabled,
        String offerId,
        String appKey,
        Product plus,
        Product pro,
        Product ultra) {

    /** 固定等级的微信道具配置，价格单位为分。 */
    public record Product(String productId, int priceCents, int durationDays) {
    }
}
