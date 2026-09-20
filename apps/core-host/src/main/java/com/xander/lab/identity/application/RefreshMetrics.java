package com.xander.lab.identity.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** V9 §87 Refresh 结果与持久化延迟指标。 */
@Component
public class RefreshMetrics {

    private final Counter requests;
    private final Counter success;
    private final Counter graceReplay;
    private final Counter reuseDetected;
    private final Counter familyRevoked;
    private final Counter invalid;
    private final Counter sessionRevoked;
    private final Counter accountDisabled;
    private final Counter databaseErrors;
    private final Counter logoutSuccess;
    private final Timer latency;
    private final AtomicLong requestCount = new AtomicLong();
    private final AtomicLong successCount = new AtomicLong();

    public RefreshMetrics(MeterRegistry meterRegistry) {
        requests = counter(meterRegistry, "refresh_requests_total",
                "Refresh rotation 请求总数");
        success = counter(meterRegistry, "refresh_success_total",
                "Refresh rotation 成功总数");
        graceReplay = counter(meterRegistry, "refresh_grace_replay_total",
                "Refresh grace 窗口内幂等重放总数");
        reuseDetected = counter(meterRegistry, "refresh_reuse_detected_total",
                "Refresh grace 外 replay 触发 reuse detection 总数");
        familyRevoked = counter(meterRegistry, "refresh_family_revoked_total",
                "因 Refresh reuse 撤销 session family 的总数");
        invalid = counter(meterRegistry, "refresh_invalid_total",
                "无效 Refresh token 总数（不包含 reuse）");
        sessionRevoked = counter(meterRegistry, "refresh_session_revoked_total",
                "已撤销或过期 Refresh Session 总数");
        accountDisabled = counter(meterRegistry, "refresh_account_disabled_total",
                "因账号封禁拒绝 Refresh 的总数");
        databaseErrors = counter(meterRegistry, "refresh_db_error_total",
                "Refresh 持久化操作失败总数");
        logoutSuccess = counter(meterRegistry, "logout_success_total",
                "使用新 Refresh 协议成功撤销当前会话的 logout 总数");
        latency = Timer.builder("refresh_db_latency")
                .description("Refresh rotation 持久化路径端到端耗时")
                .register(meterRegistry);

        Gauge.builder("refresh_success_rate", this, RefreshMetrics::successRate)
                .description("Refresh rotation 成功率；QPS 应由 refresh_requests_total 的 rate 推导")
                .register(meterRegistry);
    }

    public void recordRotation(RefreshTokenService.Outcome outcome, long elapsedNanos) {
        requests.increment();
        requestCount.incrementAndGet();
        latency.record(elapsedNanos, TimeUnit.NANOSECONDS);
        switch (outcome) {
            case ROTATED -> {
                success.increment();
                successCount.incrementAndGet();
            }
            case REPLAYED -> {
                success.increment();
                successCount.incrementAndGet();
                graceReplay.increment();
            }
            case REUSE_DETECTED -> {
                reuseDetected.increment();
                familyRevoked.increment();
            }
            case INVALID -> invalid.increment();
            case SESSION_REVOKED -> sessionRevoked.increment();
            case ACCOUNT_DISABLED -> accountDisabled.increment();
        }
    }

    public void recordDatabaseError(long elapsedNanos) {
        requests.increment();
        requestCount.incrementAndGet();
        databaseErrors.increment();
        latency.record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    public void recordLogout(boolean revoked) {
        if (revoked) {
            logoutSuccess.increment();
        }
    }

    private double successRate() {
        long total = requestCount.get();
        return total == 0L ? 0D : (double) successCount.get() / total;
    }

    private static Counter counter(MeterRegistry registry, String name, String description) {
        return Counter.builder(name).description(description).register(registry);
    }
}
