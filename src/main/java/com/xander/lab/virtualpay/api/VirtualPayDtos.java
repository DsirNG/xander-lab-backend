package com.xander.lab.virtualpay.api;

import java.time.LocalDateTime;
import java.util.List;

/** 虚拟支付公开 DTO 集合。 */
public final class VirtualPayDtos {
    private VirtualPayDtos() {
    }

    /** 后端可信商品目录项。 */
    public record Product(String productId, String name, String tier, int goodsPrice, int durationDays) {
    }

    /** 小程序调用 wx.requestVirtualPayment 所需参数。 */
    public record PayData(String outTradeNo, String mode, String signData, String paySig, String signature) {
    }

    /** 订单状态只表达本地是否已可靠发货。 */
    public record OrderStatus(String outTradeNo, String status, String tier, LocalDateTime deliveredAt) {
    }

    /** 商品目录响应，enabled=false 时前端不得展示购买入口。 */
    public record Catalog(boolean enabled, List<Product> products) {
    }
}
