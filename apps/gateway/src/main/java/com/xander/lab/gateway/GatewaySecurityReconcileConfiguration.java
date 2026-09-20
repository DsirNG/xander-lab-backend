package com.xander.lab.gateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Gateway 安全状态对账的 WebClient 组件，仅在显式开启时创建。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "gateway.security-state", name = "reconcile-enabled",
        havingValue = "true")
public class GatewaySecurityReconcileConfiguration {

    @Bean
    public GatewaySecurityStateClient gatewaySecurityStateClient(
            GatewaySecurityStateProperties properties) {
        return new GatewaySecurityStateClient(properties);
    }
}
