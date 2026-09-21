package com.xander.lab.identity.application;

import com.xander.lab.common.security.ConnectionRegistry;
import com.xander.lab.common.security.SecurityStateCache;
import com.xander.lab.core.stream.CoreHostStreamProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** V9 §85/§87 core-host 安全平面与长连接指标。 */
@Component
public class SecurityPlaneMetrics {

    public SecurityPlaneMetrics(MeterRegistry meterRegistry,
                                SecurityStateCache stateCache,
                                ConnectionRegistry connectionRegistry,
                                CoreHostStreamProperties streamProperties) {
        Tags instanceTags = Tags.of("instance", instanceLabel(streamProperties.getInstanceId()));
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
                .tags(instanceTags)
                .register(meterRegistry);
        Gauge.builder("active_ws", connectionRegistry,
                        registry -> registry.activeCountByKind(ConnectionRegistry.KIND_WS))
                .description("当前 core-host 实例活跃 WebSocket 连接数")
                .tags(instanceTags)
                .register(meterRegistry);
        Timer newRequestBlockLatency = latencyTimer(meterRegistry, "admin_ban_new_request_block_latency",
                "封禁事件传播到 core-host 后阻止新请求的延迟分布");
        Timer propagationLatency = latencyTimer(meterRegistry, "admin_ban_propagation_latency",
                "封禁事件从安全状态来源传播到 core-host 的延迟分布");
        Timer streamDisconnectLatency = latencyTimer(meterRegistry, "admin_ban_stream_disconnect_latency",
                "封禁事件关闭 core-host 已建立长连接的延迟分布");
        stateCache.setLatencyObservers(
                elapsedMillis -> {
                    newRequestBlockLatency.record(elapsedMillis, TimeUnit.MILLISECONDS);
                    propagationLatency.record(elapsedMillis, TimeUnit.MILLISECONDS);
                },
                elapsedMillis -> streamDisconnectLatency.record(elapsedMillis, TimeUnit.MILLISECONDS));
        Gauge.builder("admin_ban_new_request_block_latency_last", stateCache,
                        cache -> cache.lastPropagationLatencyMillis())
                .description("最近一次封禁传播到 core-host deny cache 的毫秒数")
                .register(meterRegistry);
        Gauge.builder("admin_ban_propagation_latency_last", stateCache,
                        cache -> cache.lastPropagationLatencyMillis())
                .description("最近一次封禁事件传播到 core-host 安全平面的毫秒数")
                .register(meterRegistry);
        Gauge.builder("admin_ban_stream_disconnect_latency_last", stateCache,
                        cache -> cache.lastStreamDisconnectLatencyMillis())
                .description("最近一次封禁关闭已建立长连接的毫秒数")
                .register(meterRegistry);
    }

    private Timer latencyTimer(MeterRegistry meterRegistry, String name, String description) {
        return Timer.builder(name)
                .description(description)
                .publishPercentiles(0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    private String instanceLabel(String instanceId) {
        return instanceId == null || instanceId.isBlank() ? "unknown" : instanceId;
    }
}
