package com.xander.lab.common.security;

import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 每实例的本地安全状态缓存。
 *
 * <p>被封禁账号集合通常很小，所以这里就是一个本地 Set 查询，成本近似 O(1)。
 * <b>不做远程 Auth Service 调用</b> —— 那样会让每个请求的可用性绑在 identity 上。</p>
 *
 * <p>本类刻意放在共享内核里且<b>只依赖 JDK</b>：它不知道账号表长什么样，
 * 也不知道是谁在往里灌数据。灌数据的是 identity 的
 * {@code SecurityStateReconciler}（{@code execution_scope = per_instance}）。</p>
 *
 * <h3>为什么每个实例各持一份，而不是共享一份</h3>
 * <p>{@code per_instance} 任务<b>不得</b>使用全局 ShedLock。加锁会让 N-1 个
 * 实例的本地缓存永不刷新，而验收指标（"不重复执行"）<b>仍然通过</b> ——
 * 这是一个不会失败的失败。</p>
 *
 * <h3>游标为什么在实例内部</h3>
 * <p>每个实例维护独立 cursor。用全局单例游标时，先到的实例会把游标推高，
 * 其他实例就永久漏掉那段区间。</p>
 */
public class SecurityStateCache {

    /** accountId → securityVersion。只在 disabled 时存在。 */
    private final Map<Long, Long> disabledAccounts = new ConcurrentHashMap<>();

    /** 本实例自己的增量游标；只被本实例的 reconciler 推进。 */
    private final AtomicLong lastSeenSeq = new AtomicLong(0L);

    /** 上次成功 reconcile 的时间戳（毫秒），供 `securityStateAge` 指标使用。 */
    private final AtomicLong lastSuccessfulSyncAt = new AtomicLong(0L);

    /** 命中缓存而拒绝的请求数，供观测使用。 */
    private final AtomicLong denialHits = new AtomicLong(0L);

    /** 最近一次带来源时间的封禁传播延迟，供 admin_ban_* 指标使用。 */
    private final AtomicLong lastPropagationLatencyMillis = new AtomicLong(0L);

    /** 最近一次实际关闭已建立流的封禁延迟。 */
    private final AtomicLong lastStreamDisconnectLatencyMillis = new AtomicLong(0L);

    /** 账号是否已被封禁。这是热路径，必须无锁且无 IO。 */
    public boolean isDisabled(Long accountId) {
        if (accountId == null) {
            return false;
        }
        boolean denied = disabledAccounts.containsKey(accountId);
        if (denied) {
            denialHits.incrementAndGet();
        }
        return denied;
    }

    /** 已封禁账号的安全版本号，不存在时返回空。 */
    public OptionalLong versionOf(Long accountId) {
        Long version = disabledAccounts.get(accountId);
        return version == null ? OptionalLong.empty() : OptionalLong.of(version);
    }

    /** 标记封禁（增量 reconcile 与快速通道都走这里，天然幂等）。 */
    public void markDisabled(Long accountId, long securityVersion) {
        if (accountId != null) {
            disabledAccounts.put(accountId, securityVersion);
        }
    }

    /** 解封。 */
    public void markEnabled(Long accountId) {
        if (accountId != null) {
            disabledAccounts.remove(accountId);
        }
    }

    /**
     * 全量替换，用于兜底对账（实例长时间离线后游标落后于历史）。
     *
     * <p>先建新集合再整体替换，避免在遍历过程中出现"部分解封"的中间态。</p>
     */
    public void replaceAll(Map<Long, Long> disabled) {
        disabledAccounts.clear();
        disabledAccounts.putAll(disabled);
    }

    /** 当前本地记录的封禁账号数，供 `service_security_cache_size` 指标使用。 */
    public int size() {
        return disabledAccounts.size();
    }

    /** 本实例已消费到的变更游标。 */
    public long lastSeenSeq() {
        return lastSeenSeq.get();
    }

    public void advanceCursor(long seq) {
        lastSeenSeq.accumulateAndGet(seq, Math::max);
    }

    public long lastSuccessfulSyncAt() {
        return lastSuccessfulSyncAt.get();
    }

    public void markSynced(long epochMillis) {
        lastSuccessfulSyncAt.set(epochMillis);
    }

    /**
     * 缓存年龄（毫秒）。{@code Long.MAX_VALUE} 表示从未成功同步过。
     *
     * <p>{@code securityStateAge} / {@code lastSuccessfulSecuritySyncAt} 必须
     * 可观测 —— 否则任何基于"stale 即拒绝"的 fail-closed 语义都不可测。</p>
     */
    public long ageMillis(long nowEpochMillis) {
        long last = lastSuccessfulSyncAt.get();
        return last == 0L ? Long.MAX_VALUE : Math.max(0L, nowEpochMillis - last);
    }

    public long denialHits() {
        return denialHits.get();
    }

    public void recordPropagationLatency(long sourceEpochMillis) {
        if (sourceEpochMillis > 0L) {
            lastPropagationLatencyMillis.set(elapsedMillis(sourceEpochMillis));
        }
    }

    public void recordStreamDisconnectLatency(long sourceEpochMillis) {
        if (sourceEpochMillis > 0L) {
            lastStreamDisconnectLatencyMillis.set(elapsedMillis(sourceEpochMillis));
        }
    }

    public long lastPropagationLatencyMillis() {
        return lastPropagationLatencyMillis.get();
    }

    public long lastStreamDisconnectLatencyMillis() {
        return lastStreamDisconnectLatencyMillis.get();
    }

    private static long elapsedMillis(long sourceEpochMillis) {
        return Math.max(0L, System.currentTimeMillis() - sourceEpochMillis);
    }

    /** 仅用于测试与诊断。 */
    public Set<Long> snapshot() {
        return Set.copyOf(disabledAccounts.keySet());
    }

    /**
     * 对一个账号集合逐个执行动作，用于"封禁后关闭已建立的长连接"。
     *
     * <p>不需要中心化全局连接表 —— 连接句柄不可跨进程迁移，
     * 中心表只会引入新的单点与一致性成本。</p>
     */
    public void forEachDisabled(Consumer<Long> action) {
        disabledAccounts.keySet().forEach(action);
    }
}
