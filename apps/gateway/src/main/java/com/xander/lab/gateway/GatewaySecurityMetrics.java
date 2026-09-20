package com.xander.lab.gateway;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** V9 §85/§87 Gateway 安全平面指标。 */
@Component
public class GatewaySecurityMetrics {

    public GatewaySecurityMetrics(MeterRegistry meterRegistry,
                                   GatewaySecurityStateCache stateCache) {
        Gauge.builder("gateway_deny_cache_size", stateCache,
                        cache -> cache.disabledCount())
                .description("当前 Gateway 实例本地 deny cache 中的封禁账号数")
                .register(meterRegistry);
        Gauge.builder("deny_reconcile_lag", stateCache,
                        cache -> cache.reconcileLag())
                .description("Gateway 源安全游标与本实例 cursor 的差值")
                .register(meterRegistry);
        Gauge.builder("security_reconcile_lag", stateCache,
                        cache -> cache.syncAgeMillis(System.currentTimeMillis()))
                .description("Gateway 距离上次成功安全状态同步的毫秒数")
                .register(meterRegistry);
    }
}
