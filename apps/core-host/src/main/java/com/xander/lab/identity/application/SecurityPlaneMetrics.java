package com.xander.lab.identity.application;

import com.xander.lab.common.security.ConnectionRegistry;
import com.xander.lab.common.security.SecurityStateCache;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** V9 §85/§87 core-host 安全平面与长连接指标。 */
@Component
public class SecurityPlaneMetrics {

    public SecurityPlaneMetrics(MeterRegistry meterRegistry,
                                SecurityStateCache stateCache,
                                ConnectionRegistry connectionRegistry) {
        Gauge.builder("service_security_cache_size", stateCache,
                        cache -> cache.size())
                .description("当前 core-host 实例本地 deny cache 中的封禁账号数")
                .register(meterRegistry);
        Gauge.builder("security_state_age", stateCache,
                        cache -> cache.ageMillis(System.currentTimeMillis()))
                .description("距上次成功安全状态同步的毫秒数")
                .register(meterRegistry);
        Gauge.builder("last_successful_security_sync_at", stateCache,
                        cache -> cache.lastSuccessfulSyncAt())
                .description("上次成功安全状态同步的 Unix 毫秒时间戳")
                .register(meterRegistry);
        Gauge.builder("security_reconcile_lag", stateCache,
                        cache -> cache.ageMillis(System.currentTimeMillis()))
                .description("core-host 安全状态对账年龄，单位毫秒")
                .register(meterRegistry);
        Gauge.builder("security_reconcile_cursor", stateCache,
                        cache -> cache.lastSeenSeq())
                .description("core-host 本实例安全状态 change_seq cursor")
                .register(meterRegistry);
        Gauge.builder("security_cache_denial_hits", stateCache,
                        cache -> cache.denialHits())
                .description("本实例命中 deny cache 而拒绝的请求数")
                .register(meterRegistry);
        Gauge.builder("active_sse", connectionRegistry,
                        registry -> registry.activeCountByKind(ConnectionRegistry.KIND_SSE))
                .description("当前 core-host 实例活跃 SSE 连接数")
                .register(meterRegistry);
        Gauge.builder("active_ws", connectionRegistry,
                        registry -> registry.activeCountByKind(ConnectionRegistry.KIND_WS))
                .description("当前 core-host 实例活跃 WebSocket 连接数")
                .register(meterRegistry);
        Gauge.builder("admin_ban_new_request_block_latency", stateCache,
                        cache -> cache.lastPropagationLatencyMillis())
                .description("最近一次封禁传播到 core-host deny cache 的毫秒数")
                .register(meterRegistry);
        Gauge.builder("admin_ban_propagation_latency", stateCache,
                        cache -> cache.lastPropagationLatencyMillis())
                .description("最近一次封禁事件传播到 core-host 安全平面的毫秒数")
                .register(meterRegistry);
        Gauge.builder("admin_ban_stream_disconnect_latency", stateCache,
                        cache -> cache.lastStreamDisconnectLatencyMillis())
                .description("最近一次封禁关闭已建立长连接的毫秒数")
                .register(meterRegistry);
    }
}
