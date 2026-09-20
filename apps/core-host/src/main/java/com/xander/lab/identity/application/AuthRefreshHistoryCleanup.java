package com.xander.lab.identity.application;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 刷新历史清理。
 *
 * <pre>
 * execution_scope:      global_once
 * singleton_strategy:   mysql_shedlock
 * batch:                500~1000，每批独立事务
 * 批间 sleep / jitter:  100~500ms
 * </pre>
 *
 * <h3>为什么是 global_once</h3>
 * <p>删除的是<b>共享</b>的历史表行。多实例同时删同一段范围只会互相锁等待，
 * 换不来任何额外吞吐 —— 这是"写共享状态"的任务，不是"各自维护本地状态"的任务
 * （对比 {@code per_instance} 的 reconciler，那种任务加全局锁才是错的）。</p>
 *
 * <h3>为什么必须慢、必须小批</h3>
 * <p>必须「全天低强度，而不是大批量集中 DELETE」。原因是 DELETE 会持有索引范围锁，
 * 而 {@code auth_refresh_token_history} 同时被轮换写入：一次 10,000 行的集中 DELETE
 * 会让正常的 refresh 请求排队等锁。容量上界是 480,000 行，
 * 所以清理的<b>吞吐</b>只要跟得上写入即可，不需要快。</p>
 *
 * <h3>依赖的索引</h3>
 * <p>{@code idx_refresh_history_retained_until}。缺了它这个 DELETE 会全表扫描，
 * 在大表上表现为"清理任务一跑，数据库就卡"。</p>
 *
 * <h3>主库</h3>
 * <p>DELETE 必须发生在 Primary。若将来读写分离，本任务必须显式绑定主库数据源，
 * 不得跟随只读路由 —— 在 Replica 上执行 DELETE 会直接失败或造成主从分歧。</p>
 */
@Slf4j
@Component
public class AuthRefreshHistoryCleanup {

    private final AuthRefreshRetentionService retentionService;
    private final RetentionBatchLoop batchLoop;
    private final RefreshTokenProperties properties;
    private final Clock clock;
    private final RefreshRetentionMetrics metrics;

    @Autowired
    public AuthRefreshHistoryCleanup(AuthRefreshRetentionService retentionService,
                                     RetentionBatchLoop batchLoop,
                                     RefreshTokenProperties properties,
                                     RefreshRetentionMetrics metrics) {
        this(retentionService, batchLoop, properties, Clock.systemDefaultZone(), metrics);
    }

    /** 供测试注入固定时钟，让本轮的时间边界可被确定性断言。 */
    AuthRefreshHistoryCleanup(AuthRefreshRetentionService retentionService,
                              RetentionBatchLoop batchLoop,
                              RefreshTokenProperties properties,
                              Clock clock) {
        this(retentionService, batchLoop, properties, clock, null);
    }

    AuthRefreshHistoryCleanup(AuthRefreshRetentionService retentionService,
                              RetentionBatchLoop batchLoop,
                              RefreshTokenProperties properties,
                              Clock clock,
                              RefreshRetentionMetrics metrics) {
        this.retentionService = retentionService;
        this.batchLoop = batchLoop;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
    }

    /**
     * 分批删除已过保留期的历史行。
     *
     * <p>固定延迟调度：上一轮结束后再等 interval，所以一轮跑久了会自动让出时间，
     * 不会出现两轮叠在一起。</p>
     */
    @Scheduled(fixedDelayString = "${auth.refresh.history-cleanup-interval-ms:300000}")
    @SchedulerLock(name = "auth-refresh-history-cleanup", lockAtMostFor = "PT5M")
    public void cleanupExpiredHistory() {
        int batchSize = Math.max(1, properties.getHistoryCleanupBatchSize());
        int maxBatches = Math.max(1, properties.getHistoryCleanupMaxBatches());
        long startedAt = System.nanoTime();
        int deleted = batchLoop.run("历史清理", LocalDateTime.now(clock), batchSize, maxBatches,
                retentionService::deleteExpiredHistory);
        if (metrics != null) {
            metrics.recordHistoryCleanup(deleted, System.nanoTime() - startedAt);
        }
        if (deleted > 0) {
            log.info("[Refresh] 历史清理本轮删除 {} 行", deleted);
        }
    }
}
