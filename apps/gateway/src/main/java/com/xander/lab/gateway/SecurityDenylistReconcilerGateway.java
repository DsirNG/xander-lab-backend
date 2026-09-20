package com.xander.lab.gateway;

import com.xander.lab.core.api.SecurityStateSyncApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Gateway 每实例安全状态对账器。
 *
 * <p>不使用 ShedLock：cursor 与 deny cache 都属于当前 Gateway 实例。启动先做完整快照
 * + watermark 之后的增量重放，运行期按 1.5 秒增量对账，并在低频周期做全量校准。</p>
 */
@Component
@ConditionalOnProperty(prefix = "gateway.security-state", name = "reconcile-enabled",
        havingValue = "true")
public class SecurityDenylistReconcilerGateway implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SecurityDenylistReconcilerGateway.class);

    private final GatewaySecurityStateClient stateClient;
    private final GatewaySecurityStateCache stateCache;
    private final GatewaySecurityStateProperties properties;
    private final GatewaySecurityPlaneReadiness readiness;
    private volatile long lastFullReconcileAt;

    public SecurityDenylistReconcilerGateway(GatewaySecurityStateClient stateClient,
                                             GatewaySecurityStateCache stateCache,
                                             GatewaySecurityStateProperties properties,
                                             GatewaySecurityPlaneReadiness readiness) {
        this.stateClient = stateClient;
        this.stateCache = stateCache;
        this.properties = properties;
        this.readiness = readiness;
        validateProperties();
    }

    @Override
    public void run(ApplicationArguments args) {
        long startedAt = System.currentTimeMillis();
        try {
            fullSnapshotAndReplay();
            long now = System.currentTimeMillis();
            lastFullReconcileAt = now;
            stateCache.markSynced(now);
            readiness.markReady(now);
            log.info("[GatewaySecurity] Bootstrap 完成：封禁账号={} 水位={} 耗时={} ms",
                    stateCache.disabledCount(), stateCache.lastSeenSeq(), now - startedAt);
        } catch (RuntimeException ex) {
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            readiness.markFailed(reason);
            log.error("[GatewaySecurity] Bootstrap 失败，拒绝 Gateway 进入就绪状态: {}", reason, ex);
            throw new IllegalStateException("Gateway 安全平面 Bootstrap 失败: " + reason, ex);
        }
    }

    @Scheduled(fixedDelayString = "${gateway.security-state.reconcile-interval-ms:1500}")
    public void reconcile() {
        if (!readiness.isReady()) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            if (now - lastFullReconcileAt >= properties.getFullReconcileIntervalMs()) {
                fullSnapshotAndReplay();
                lastFullReconcileAt = now;
            } else {
                incrementalReconcile();
            }
            stateCache.markSynced(now);
        } catch (RuntimeException ex) {
            // 保留上一份已知安全状态；下一轮以同一 cursor 重试，避免短暂 core-host 故障抹空缓存。
            log.warn("[GatewaySecurity] 对账失败，将在下一轮重试: {}", ex.getMessage());
        }
    }

    private void fullSnapshotAndReplay() {
        SecurityStateSyncApi.Snapshot snapshot = stateClient.snapshot();
        Map<Long, GatewaySecurityStateCache.State> states = new HashMap<>();
        for (SecurityStateSyncApi.Entry entry : snapshot.states()) {
            states.put(entry.accountId(),
                    new GatewaySecurityStateCache.State(entry.disabled(), entry.securityVersion()));
        }
        stateCache.replaceAll(states, snapshot.watermark());
        stateCache.markSourceWatermark(snapshot.watermark());
        replayAfter(snapshot.watermark());
    }

    private void incrementalReconcile() {
        SecurityStateSyncApi.Changes changes = stateClient.changes(
                stateCache.lastSeenSeq(), properties.getReconcileBatchSize());
        applyChanges(changes);
    }

    private void replayAfter(long cursor) {
        while (true) {
            SecurityStateSyncApi.Changes changes = stateClient.changes(
                    cursor, properties.getReconcileBatchSize());
            stateCache.markSourceWatermark(changes.watermark());
            if (changes.changes().isEmpty()) {
                return;
            }
            long nextCursor = applyChanges(cursor, changes);
            if (changes.changes().size() < properties.getReconcileBatchSize()) {
                return;
            }
            cursor = nextCursor;
        }
    }

    private void applyChanges(SecurityStateSyncApi.Changes changes) {
        applyChanges(stateCache.lastSeenSeq(), changes);
    }

    private long applyChanges(long cursor, SecurityStateSyncApi.Changes changes) {
        stateCache.markSourceWatermark(changes.watermark());
        long nextCursor = cursor;
        for (SecurityStateSyncApi.Entry entry : changes.changes()) {
            stateCache.apply(entry.accountId(), entry.disabled(), entry.securityVersion());
            nextCursor = Math.max(nextCursor, entry.changeSeq());
        }
        if (nextCursor <= cursor) {
            throw new IllegalStateException("安全状态对账返回变更但 cursor 未推进: " + cursor);
        }
        stateCache.advanceCursor(nextCursor);
        return nextCursor;
    }

    private void validateProperties() {
        if (properties.getReconcileIntervalMs() < 100L
                || properties.getReconcileIntervalMs() > 2000L) {
            throw new IllegalStateException("Gateway 安全状态对账间隔必须在 100~2000ms 内");
        }
        if (properties.getFullReconcileIntervalMs() < properties.getReconcileIntervalMs()) {
            throw new IllegalStateException("Gateway 全量对账间隔不能小于增量对账间隔");
        }
        if (properties.getReconcileBatchSize() < 1 || properties.getReconcileBatchSize() > 2000) {
            throw new IllegalStateException("Gateway 安全状态对账批次必须在 1~2000 内");
        }
    }
}
