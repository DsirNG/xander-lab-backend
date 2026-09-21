package com.xander.lab.agent.infrastructure;

import com.xander.lab.common.security.ConnectionRegistry;
import com.xander.lab.common.security.SecurityStateCache;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** Agent 安全平面与长连接封禁 SLO 指标。 */
@Component
public class AgentSecurityPlaneMetrics {

    public AgentSecurityPlaneMetrics(MeterRegistry meterRegistry,
                                     SecurityStateCache stateCache,
                                     ConnectionRegistry connectionRegistry) {
        Gauge.builder("service_security_cache_size", stateCache, SecurityStateCache::size)
                .description("当前 Agent 实例本地 deny cache 中的封禁账号数")
                .register(meterRegistry);
        Gauge.builder("security_state_age", stateCache,
                        cache -> cache.ageMillis(System.currentTimeMillis()))
                .description("距上次成功安全状态同步的毫秒数")
                .register(meterRegistry);
        Gauge.builder("last_successful_security_sync_at", stateCache,
                        SecurityStateCache::lastSuccessfulSyncAt)
                .description("上次成功安全状态同步的 Unix 毫秒时间戳")
                .register(meterRegistry);
        Gauge.builder("security_reconcile_lag", stateCache,
                        cache -> cache.ageMillis(System.currentTimeMillis()))
                .description("Agent 安全状态对账年龄，单位毫秒")
                .register(meterRegistry);
        Gauge.builder("security_reconcile_cursor", stateCache,
                        SecurityStateCache::lastSeenSeq)
                .description("Agent 本实例安全状态 change_seq cursor")
                .register(meterRegistry);
        Gauge.builder("security_cache_denial_hits", stateCache,
                        SecurityStateCache::denialHits)
                .description("Agent 本实例命中 deny cache 而拒绝的请求数")
                .register(meterRegistry);
        Gauge.builder("active_sse", connectionRegistry,
                        registry -> registry.activeCountByKind(ConnectionRegistry.KIND_SSE))
                .description("当前 Agent 实例活跃 SSE 连接数")
                .register(meterRegistry);
        Gauge.builder("active_ws", connectionRegistry,
                        registry -> registry.activeCountByKind(ConnectionRegistry.KIND_WS))
                .description("当前 Agent 实例活跃 WebSocket 连接数")
                .register(meterRegistry);

        Timer newRequestBlockLatency = latencyTimer(meterRegistry,
                "admin_ban_new_request_block_latency", "封禁事件传播到 Agent 后阻止新请求的延迟分布");
        Timer propagationLatency = latencyTimer(meterRegistry,
                "admin_ban_propagation_latency", "封禁事件从安全状态来源传播到 Agent 的延迟分布");
        Timer streamDisconnectLatency = latencyTimer(meterRegistry,
                "admin_ban_stream_disconnect_latency", "封禁事件关闭 Agent 已建立长连接的延迟分布");
        stateCache.setLatencyObservers(
                elapsedMillis -> {
                    newRequestBlockLatency.record(elapsedMillis, TimeUnit.MILLISECONDS);
                    propagationLatency.record(elapsedMillis, TimeUnit.MILLISECONDS);
                },
                elapsedMillis -> streamDisconnectLatency.record(elapsedMillis, TimeUnit.MILLISECONDS));
        Gauge.builder("admin_ban_new_request_block_latency_last", stateCache,
                        SecurityStateCache::lastPropagationLatencyMillis)
                .description("最近一次封禁传播到 Agent deny cache 的毫秒数")
                .register(meterRegistry);
        Gauge.builder("admin_ban_propagation_latency_last", stateCache,
                        SecurityStateCache::lastPropagationLatencyMillis)
                .description("最近一次封禁事件传播到 Agent 安全平面的毫秒数")
                .register(meterRegistry);
        Gauge.builder("admin_ban_stream_disconnect_latency_last", stateCache,
                        SecurityStateCache::lastStreamDisconnectLatencyMillis)
                .description("最近一次封禁关闭 Agent 已建立长连接的毫秒数")
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
