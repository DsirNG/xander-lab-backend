package com.xander.lab.virtualpay.infrastructure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 个人主体虚拟支付网络与回调配置；业务配置由管理台加密入库。 */
@Data
@Validated
@ConfigurationProperties(prefix = "wechat.virtual-pay")
public class VirtualPayProperties {
    private String messageToken = "";
    private String apiBaseUrl = "https://api.weixin.qq.com";
    private long connectTimeoutMillis = 5000;
    private long readTimeoutMillis = 10000;
}
