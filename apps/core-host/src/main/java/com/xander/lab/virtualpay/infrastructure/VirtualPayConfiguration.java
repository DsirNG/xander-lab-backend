package com.xander.lab.virtualpay.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册个人主体虚拟支付的类型化配置。 */
@Configuration
@EnableConfigurationProperties(VirtualPayProperties.class)
public class VirtualPayConfiguration {
}
