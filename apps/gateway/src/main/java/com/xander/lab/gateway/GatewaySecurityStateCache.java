package com.xander.lab.gateway;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gateway 每实例的账号封禁缓存。
 *
 * <p>它只保存事件传播到本实例后的结果，不是安全状态 SOT。跨重启的快照/增量对账
 * 必须由后续 Gateway reconciler 提供；缓存缺失时不会伪装成已完成同步。</p>
 */
@Component
public class GatewaySecurityStateCache {

    private final Map<Long, State> states = new ConcurrentHashMap<>();
    private final AtomicLong lastSeenSeq = new AtomicLong();
    private final AtomicLong sourceWatermark = new AtomicLong();
    private final AtomicLong lastSuccessfulSyncAt = new AtomicLong();

    public boolean isDisabled(long accountId) {
        State state = states.get(accountId);
        return state != null && state.disabled();
    }

    /** 版本单调地应用封禁/解封事件，重复或迟到事件无副作用。 */
    public synchronized void apply(long accountId, boolean disabled, long securityVersion) {
        states.compute(accountId, (ignored, current) -> {
            if (current != null && current.securityVersion() >= securityVersion) {
                return current;
            }
            return new State(disabled, securityVersion);
        });
    }

    /** 用 core-host 的完整快照替换本实例状态，包含已解封账号以保留版本闸门。 */
    public synchronized void replaceAll(Map<Long, State> snapshot, long cursor) {
        states.clear();
        states.putAll(snapshot);
        lastSeenSeq.set(cursor);
    }

    public long lastSeenSeq() {
        return lastSeenSeq.get();
    }

    public synchronized void advanceCursor(long cursor) {
        lastSeenSeq.accumulateAndGet(cursor, Math::max);
    }

    public void markSourceWatermark(long watermark) {
        sourceWatermark.accumulateAndGet(watermark, Math::max);
    }

    public long sourceWatermark() {
        return sourceWatermark.get();
    }

    public void markSynced(long epochMillis) {
        lastSuccessfulSyncAt.set(epochMillis);
    }

    public long syncAgeMillis(long nowEpochMillis) {
        long last = lastSuccessfulSyncAt.get();
        return last == 0L ? Long.MAX_VALUE : Math.max(0L, nowEpochMillis - last);
    }

    public long reconcileLag() {
        return Math.max(0L, sourceWatermark() - lastSeenSeq());
    }

    public int disabledCount() {
        int count = 0;
        for (State state : states.values()) {
            if (state.disabled()) {
                count++;
            }
        }
        return count;
    }

    public record State(boolean disabled, long securityVersion) {
    }
}
