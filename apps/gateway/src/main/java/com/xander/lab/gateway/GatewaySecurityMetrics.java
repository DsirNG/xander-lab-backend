package com.xander.lab.gateway;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

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
        Timer newRequestBlockLatency = latencyTimer(meterRegistry, "admin_ban_new_request_block_latency",
                "封禁事件传播到 Gateway 后阻止新请求的延迟分布");
        Timer propagationLatency = latencyTimer(meterRegistry, "admin_ban_propagation_latency",
                "封禁事件从安全状态来源传播到 Gateway 的延迟分布");
        stateCache.setPropagationLatencyObserver(elapsedMillis -> {
            newRequestBlockLatency.record(elapsedMillis, TimeUnit.MILLISECONDS);
            propagationLatency.record(elapsedMillis, TimeUnit.MILLISECONDS);
        });
        Gauge.builder("admin_ban_new_request_block_latency_last", stateCache,
                        cache -> cache.lastPropagationLatencyMillis())
                .description("最近一次封禁传播到 Gateway deny cache 的毫秒数")
                .register(meterRegistry);
        Gauge.builder("admin_ban_propagation_latency_last", stateCache,
                        cache -> cache.lastPropagationLatencyMillis())
                .description("最近一次封禁事件传播到 Gateway 安全平面的毫秒数")
                .register(meterRegistry);
    }

    private Timer latencyTimer(MeterRegistry meterRegistry, String name, String description) {
        return Timer.builder(name)
                .description(description)
                .publishPercentiles(0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry);
    }
}
