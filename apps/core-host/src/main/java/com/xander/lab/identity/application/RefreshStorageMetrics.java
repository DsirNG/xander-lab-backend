package com.xander.lab.identity.application;

import com.xander.lab.identity.infrastructure.AuthRefreshTokenHistoryMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/** V9 §87 Refresh 表行数观测。 */
@Slf4j
@Component
public class RefreshStorageMetrics {

    private static final long OBSERVE_INTERVAL_MS = 60_000L;

    private final AuthRefreshTokenHistoryMapper historyMapper;
    private final AtomicLong sessionRows = new AtomicLong();
    private final AtomicLong historyRows = new AtomicLong();

    public RefreshStorageMetrics(MeterRegistry meterRegistry,
                                 AuthRefreshTokenHistoryMapper historyMapper) {
        this.historyMapper = historyMapper;
        Gauge.builder("refresh_session_rows", sessionRows, AtomicLong::doubleValue)
                .description("auth_refresh_session 当前行数")
                .register(meterRegistry);
        Gauge.builder("refresh_history_rows", historyRows, AtomicLong::doubleValue)
                .description("auth_refresh_token_history 当前行数")
                .register(meterRegistry);
    }

    /**
     * 低频只读观测；不把 SQL 放进 Gauge scrape 回调，避免 Prometheus 抓取阻塞数据库连接。
     * 每个实例各自维护最近一次结果，不使用全局锁，以便每个实例都能暴露自己的观测状态。
     */
    @Scheduled(fixedDelay = OBSERVE_INTERVAL_MS, initialDelay = OBSERVE_INTERVAL_MS)
    public void refresh() {
        try {
            sessionRows.set(historyMapper.countSessions());
            historyRows.set(historyMapper.countAll());
        } catch (RuntimeException ex) {
            // 观测失败不能影响 refresh 主链路；保留上次值并等待下一轮。
            log.warn("[Refresh] 表行数观测失败，保留上次值：{}", ex.getMessage());
        }
    }
}
