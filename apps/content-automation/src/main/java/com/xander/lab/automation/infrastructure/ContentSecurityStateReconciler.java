package com.xander.lab.automation.infrastructure;

import com.xander.lab.automation.host.ContentSecurityStateProperties;
import com.xander.lab.common.security.ConnectionRegistry;
import com.xander.lab.common.security.SecurityPlaneReadiness;
import com.xander.lab.common.security.SecurityStateCache;
import com.xander.lab.core.api.SecurityStateSyncApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/** content-automation 每实例安全状态对账器；不使用全局锁。 */
@Component
@ConditionalOnProperty(prefix = "content-automation.security-state", name = "reconcile-enabled",
        havingValue = "true")
public class ContentSecurityStateReconciler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ContentSecurityStateReconciler.class);

    private final ContentSecurityStateClient stateClient;
    private final ContentSecurityStateProperties properties;
    private final SecurityStateCache stateCache;
    private final ConnectionRegistry connectionRegistry;
    private final SecurityPlaneReadiness readiness;
    private volatile long lastFullReconcileAt;

    public ContentSecurityStateReconciler(ContentSecurityStateClient stateClient,
                                           ContentSecurityStateProperties properties,
                                           SecurityStateCache stateCache,
                                           ConnectionRegistry connectionRegistry,
                                           SecurityPlaneReadiness readiness) {
        this.stateClient = stateClient;
        this.properties = properties;
        this.stateCache = stateCache;
        this.connectionRegistry = connectionRegistry;
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
            log.info("[ContentSecurity] Bootstrap 完成：封禁账号={} 水位={} 耗时={} ms",
                    stateCache.size(), stateCache.lastSeenSeq(), now - startedAt);
        } catch (RuntimeException ex) {
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            readiness.markFailed(reason);
            log.error("[ContentSecurity] Bootstrap 失败，拒绝进入安全平面就绪状态: {}", reason, ex);
            throw new IllegalStateException("content-automation 安全平面 Bootstrap 失败: " + reason, ex);
        }
    }

    @Scheduled(fixedDelayString = "${content-automation.security-state.reconcile-interval-ms:1500}")
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
                applyChanges(stateClient.changes(stateCache.lastSeenSeq(),
                        properties.getReconcileBatchSize()));
            }
            stateCache.markSynced(now);
        } catch (RuntimeException ex) {
            log.warn("[ContentSecurity] 对账失败，将在下一轮重试: {}", ex.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${content-automation.security-state.full-reconcile-interval-ms:300000}")
    public void fullReconcile() {
        if (!readiness.isReady()) {
            return;
        }
        try {
            fullSnapshotAndReplay();
            lastFullReconcileAt = System.currentTimeMillis();
            stateCache.markSynced(System.currentTimeMillis());
        } catch (RuntimeException ex) {
            log.warn("[ContentSecurity] 全量对账失败: {}", ex.getMessage());
        }
    }

    private void fullSnapshotAndReplay() {
        SecurityStateSyncApi.Snapshot snapshot = stateClient.snapshot();
        Set<Long> previouslyDisabled = stateCache.snapshot();
        Map<Long, Long> disabled = new HashMap<>();
        for (SecurityStateSyncApi.Entry entry : snapshot.states()) {
            if (entry.disabled()) {
                disabled.put(entry.accountId(), entry.securityVersion());
            }
        }
        stateCache.replaceAll(disabled);
        for (SecurityStateSyncApi.Entry entry : snapshot.states()) {
            if (entry.disabled() && !previouslyDisabled.contains(entry.accountId())) {
                closeNewlyDisabledConnection(entry);
            }
        }
        replayAfter(snapshot.watermark());
    }

    private void replayAfter(long cursor) {
        while (true) {
            SecurityStateSyncApi.Changes changes = stateClient.changes(
                    cursor, properties.getReconcileBatchSize());
            if (changes.changes().isEmpty()) {
                stateCache.advanceCursor(Math.max(cursor, changes.watermark()));
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
        long cursor = stateCache.lastSeenSeq();
        if (changes.changes().isEmpty()) {
            stateCache.advanceCursor(Math.max(cursor, changes.watermark()));
            return;
        }
        applyChanges(cursor, changes);
    }

    private long applyChanges(long cursor, SecurityStateSyncApi.Changes changes) {
        long nextCursor = cursor;
        for (SecurityStateSyncApi.Entry entry : changes.changes()) {
            apply(entry);
            nextCursor = Math.max(nextCursor, entry.changeSeq());
        }
        if (nextCursor <= cursor) {
            throw new IllegalStateException("安全状态对账返回变更但 cursor 未推进: " + cursor);
        }
        stateCache.advanceCursor(nextCursor);
        return nextCursor;
    }

    private void apply(SecurityStateSyncApi.Entry entry) {
        if (entry.accountId() <= 0 || entry.changeSeq() <= 0) {
            throw new IllegalStateException("安全状态对账返回非法变更");
        }
        if (!entry.disabled()) {
            stateCache.markEnabled(entry.accountId());
            return;
        }
        OptionalLong cachedVersion = stateCache.versionOf(entry.accountId());
        if (cachedVersion.isPresent() && cachedVersion.getAsLong() >= entry.securityVersion()) {
            return;
        }
        boolean wasDisabled = stateCache.isDisabled(entry.accountId());
        stateCache.markDisabled(entry.accountId(), entry.securityVersion());
        stateCache.recordPropagationLatency(entry.occurredAtEpochMillis());
        if (!wasDisabled) {
            closeNewlyDisabledConnection(entry);
        }
    }

    private void closeNewlyDisabledConnection(SecurityStateSyncApi.Entry entry) {
        int closed = connectionRegistry.closeAll(entry.accountId(),
                ConnectionRegistry.REASON_ACCOUNT_DISABLED);
        if (closed > 0) {
            stateCache.recordStreamDisconnectLatency(entry.occurredAtEpochMillis());
            log.warn("[ContentSecurity] 封禁生效，关闭已建立连接 account={} closed={}",
                    entry.accountId(), closed);
        }
    }

    private void validateProperties() {
        if (properties.getReconcileIntervalMs() < 100L
                || properties.getReconcileIntervalMs() > 2000L) {
            throw new IllegalStateException("content-automation 安全状态对账间隔必须在 100~2000ms 内");
        }
        if (properties.getFullReconcileIntervalMs() < properties.getReconcileIntervalMs()) {
            throw new IllegalStateException("content-automation 全量对账间隔不能小于增量对账间隔");
        }
        if (properties.getReconcileBatchSize() < 1 || properties.getReconcileBatchSize() > 2000) {
            throw new IllegalStateException("content-automation 安全状态对账批次必须在 1~2000 内");
        }
    }
}
