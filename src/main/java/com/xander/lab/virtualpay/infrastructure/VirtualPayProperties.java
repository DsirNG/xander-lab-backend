package com.xander.lab.virtualpay.infrastructure;

import jakarta.validation.Valid;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/** 个人主体虚拟支付现网配置；密钥只允许来自服务端环境变量。 */
@Data
@Validated
@ConfigurationProperties(prefix = "wechat.virtual-pay")
public class VirtualPayProperties {
    private boolean enabled = false;
    private String offerId = "";
    private String appKey = "";
    private String messageToken = "";
    private String apiBaseUrl = "https://api.weixin.qq.com";
    private long connectTimeoutMillis = 5000;
    private long readTimeoutMillis = 10000;
    @Valid
    private List<Product> products = List.of();

    /** 后端可信商品定义；金额单位始终为分。 */
    @Data
    public static class Product {
        private String productId = "";
        private String name = "";
        private String tier = "";
        private int goodsPrice;
        private int durationDays;
    }
}
