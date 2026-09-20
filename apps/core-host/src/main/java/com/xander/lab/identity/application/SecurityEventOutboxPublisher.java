package com.xander.lab.identity.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.identity.domain.SecurityEventOutboxEntry;
import com.xander.lab.identity.infrastructure.SecurityEventOutboxMapper;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 安全事件发件箱投递器 —— 封禁传播的<b>快路径</b>。
 *
 * <pre>
 * execution_scope:      global_once
 * singleton_strategy:   mysql_shedlock
 * </pre>
 *
 * <h3>它解决什么问题</h3>
 * <p>管理员封禁的事务（{@link AccountSecurityStateService#applyState}）在提交前只能写库：
 * "把 accountId 推进每个宿主的 deny cache""关闭已建立的 SSE / WS"都要触碰别的进程，
 * 不可能在事务内完成。于是事件先落库，提交后由本类投递 —— 这就是封禁能扛住
 * "提交后立刻崩溃"的原因。</p>
 *
 * <h3>为什么是 global_once</h3>
 * <p>本类的职责是"<b>领取并投递</b>"：{@code claimDue} 领取的是<b>共享的</b>发件箱行，
 * 投递成功后要把行标成 {@code PUBLISHED}。这份领取/标记状态是所有实例共用的，
 * 所以执行体只能有一个 —— 否则 N 个实例会各自领取同一批行、各自标记，
 * 而"已投递"变成了 N 份互相矛盾的判断。</p>
 *
 * <p>跨实例传播因此<b>不是调度器的事，而是投递目标的事</b>：默认投递目标是本实例的
 * 安全平面（{@link SecurityStateApplier}）；开启 {@code auth.refresh.security-pubsub-enabled}
 * 后则投递到 Redis Pub/Sub，由每个实例的订阅者应用到本地安全平面。领取/标记/退避的
 * 生命周期不变。</p>
 *
 * <h3>⚠️ 当前单实例部署的能力边界（不得误报）</h3>
 * <p>关闭 Pub/Sub 时快路径只对抢到锁的那个实例生效，其余实例靠各自的
 * {@link SecurityStateReconciler} 在 1.5 秒内收敛；开启 Pub/Sub 后所有订阅实例都会收到
 * 同一事件。无论哪种模式，本类都承担另一个不可省略的职责：<b>让
 * {@code security_event_outbox} 有界</b>（没有消费者时它会无界增长）。</p>
 *
 * <h3>⚠️ 本类刻意不推进 reconciler 的游标</h3>
 * <p>看起来"投递成功就把游标推到该事件的 changeSeq"很自然，但那会制造一个永久漏封禁：
 * {@code change_seq} 在事务里分配，而发件箱行的可见性取决于<b>提交顺序</b> ——
 * 两个并发封禁可能让 changeSeq=6 先提交、changeSeq=5 后提交。此时本类只看得见 6，
 * 若把游标推到 6，则 5 提交后会被 {@code pullChangesAfter} 永久跳过，
 * 那个账号的封禁<b>永远不会</b>到达本实例。</p>
 * <p>所以游标只有一个所有者：{@link SecurityStateReconciler}。两条路径对同一变更的
 * 重复应用是无害的（{@link SecurityStateApplier} 幂等且只在状态翻转时断连）。</p>
 *
 * <h3>失败处理</h3>
 * <ul>
 *   <li>单条事件失败<b>不会</b>中断整批：该条记 {@code FAILED} + 指数退避，其余照常投递。</li>
 *   <li>超过 {@link #MAX_ATTEMPTS} 次仍失败 → 记 {@code DEAD}，不再自动重试。
 *       判死是安全的：发件箱只负责通知，SOT 是 {@code account_security_state}，
 *       兜底路径仍会收敛（详见 {@code STATUS_DEAD} 的说明）。</li>
 *   <li>{@code markPublished} 本身失败（如投递后数据库抖动）会让行留在 {@code PENDING}，
 *       下一轮重复投递 —— 这正是投递端必须幂等的原因。</li>
 * </ul>
 */
@Slf4j
@Component
public class SecurityEventOutboxPublisher {

    /**
     * 单条事件的重试上界。
     *
     * <p>取 8 而不是 3：退避是指数的，8 次的总时长已经跨过分钟级，足以吸收一次短暂的
     * 数据库/网络抖动；而 3 次（约 7 秒）会把一次普通的瞬时故障误判成永久故障。</p>
     */
    public static final int MAX_ATTEMPTS = 8;

    /** 退避基数：第 1 次失败后退避 1 秒。 */
    private static final long BASE_BACKOFF_MS = 1_000L;

    /** 退避上界：60 秒。再长就没有意义了 —— 兜底路径早已收敛。 */
    private static final long MAX_BACKOFF_MS = 60_000L;

    /** {@code last_error} 列宽 500，留出截断标记的余量。 */
    private static final int MAX_ERROR_LENGTH = 480;

    private final SecurityEventOutboxMapper outboxMapper;
    private final SecurityStateApplier securityStateApplier;
    private final RefreshTokenProperties properties;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<SecurityEventBroadcaster> broadcasterProvider;

    /** 保留无广播器构造路径，保证本地单元测试与单实例模式不依赖 Redis Pub/Sub。 */
    public SecurityEventOutboxPublisher(SecurityEventOutboxMapper outboxMapper,
                                        SecurityStateApplier securityStateApplier,
                                        RefreshTokenProperties properties,
                                        ObjectMapper objectMapper) {
        this(outboxMapper, securityStateApplier, properties, objectMapper, null);
    }

    @Autowired
    public SecurityEventOutboxPublisher(SecurityEventOutboxMapper outboxMapper,
                                        SecurityStateApplier securityStateApplier,
                                        RefreshTokenProperties properties,
                                        ObjectMapper objectMapper,
                                        ObjectProvider<SecurityEventBroadcaster> broadcasterProvider) {
        this.outboxMapper = outboxMapper;
        this.securityStateApplier = securityStateApplier;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.broadcasterProvider = broadcasterProvider;
    }

    /**
     * 领取到期的安全事件并投递。
     *
     * <p>固定延迟调度：上一轮结束后再等 interval，避免慢批次把调度器压垮。</p>
     */
    @Scheduled(fixedDelayString = "${auth.refresh.security-outbox-publish-interval-ms:1000}")
    @SchedulerLock(name = "security-event-outbox-publisher", lockAtMostFor = "PT1M")
    public void publishDueEvents() {
        try {
            List<SecurityEventOutboxEntry> due =
                    outboxMapper.claimDue(properties.getSecurityOutboxPublishBatchSize());
            if (due.isEmpty()) {
                return;
            }

            int published = 0;
            int retryable = 0;
            int parked = 0;
            for (SecurityEventOutboxEntry entry : due) {
                try {
                    deliver(entry);
                    outboxMapper.markPublished(entry.getId());
                    published++;
                } catch (RuntimeException ex) {
                    if (recordFailure(entry, ex)) {
                        parked++;
                    } else {
                        retryable++;
                    }
                }
            }
            if (retryable > 0 || parked > 0) {
                log.warn("[Security] outbox 投递：成功={} 待重试={} 已判死={}", published, retryable, parked);
            } else {
                log.debug("[Security] outbox 投递：成功={}", published);
            }
        } catch (RuntimeException ex) {
            // 整轮失败（如领取时数据库不可用）不能中断调度；下一轮重试同一批（幂等）。
            log.warn("[Security] outbox 投递轮次失败，将在下一轮重试: {}", ex.getMessage());
        }
    }

    /**
     * 投递单条事件到本实例的安全平面。
     *
     * @throws RuntimeException 负载畸形、事件类型未知、或应用失败；由调用方记入退避
     */
    private void deliver(SecurityEventOutboxEntry entry) {
        SecurityEventBroadcaster broadcaster = broadcasterProvider == null
                ? null : broadcasterProvider.getIfAvailable();
        if (broadcaster != null) {
            broadcaster.publish(entry);
            return;
        }
        deliverLocally(entry);
    }

    /**
     * 应用到当前实例，不再转发到 Pub/Sub。
     *
     * <p>Redis 订阅者必须走这条路径，否则订阅者收到消息后会再次广播，形成回环。</p>
     */
    void deliverLocally(SecurityEventOutboxEntry entry) {
        String eventType = entry.getEventType();
        if (SecurityEventOutboxEntry.TYPE_ACCOUNT_DISABLED.equals(eventType)) {
            applyAccountState(entry, true);
            return;
        }
        if (SecurityEventOutboxEntry.TYPE_ACCOUNT_ENABLED.equals(eventType)) {
            applyAccountState(entry, false);
            return;
        }
        if (SecurityEventOutboxEntry.TYPE_REFRESH_TOKEN_REUSE.equals(eventType)) {
            // 家族撤销已经在检测到重用的那个写事务里完成，本地安全平面无需动作。
            // 这里显式确认而不是留给 default 分支：否则这条事件会被误判成"未知类型"并判死。
            log.debug("[Security] 收到 {} 事件，本地安全平面无需动作 aggregate={}",
                    eventType, entry.getAggregateId());
            return;
        }
        // 未知类型通常是"新版本写了新事件、旧实例还在跑"。判死并留痕，
        // 而不是无限重试——版本错配不会因为重试而自愈。
        throw new IllegalStateException("本实例不认识的安全事件类型: " + eventType);
    }

    private void applyAccountState(SecurityEventOutboxEntry entry, boolean disabled) {
        Payload payload = parsePayload(entry);
        if (!String.valueOf(payload.accountId()).equals(entry.getAggregateId())) {
            // 负载与聚合标识必须指向同一个账号。不一致说明写入方有 bug，
            // 此时按哪个 id 执行都是错的 —— 让它失败并留痕，不要猜。
            throw new IllegalStateException("事件负载 accountId=" + payload.accountId()
                    + " 与 aggregate_id=" + entry.getAggregateId() + " 不一致");
        }
        SecurityStateApplier.Outcome outcome =
                securityStateApplier.apply(payload.accountId(), disabled, payload.securityVersion());
        if (outcome.newlyDisabled()) {
            log.warn("[Security] 快路径封禁生效 account={} version={} 关闭连接={}",
                    payload.accountId(), payload.securityVersion(), outcome.closedConnections());
        } else if (outcome.newlyEnabled()) {
            log.info("[Security] 快路径解封生效 account={}", payload.accountId());
        }
    }

    /**
     * 从负载里取出投递所需的两个字段。
     *
     * <p>用 Jackson 读而不是手写字符串截取：负载的写入方是
     * {@link AccountSecurityStateService#writeOutboxEvent}，它对 {@code reason}
     * 做了转义 —— 手写解析器一旦把转义后的引号当成结构分隔符，就会把
     * {@code reason} 里的数字读成 accountId。</p>
     */
    private Payload parsePayload(SecurityEventOutboxEntry entry) {
        String raw = entry.getPayload();
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("事件负载为空 id=" + entry.getId());
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(raw);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("事件负载不是合法 JSON id=" + entry.getId()
                    + " : " + ex.getOriginalMessage(), ex);
        }
        JsonNode accountId = node.get("accountId");
        if (accountId == null || !accountId.canConvertToLong()) {
            throw new IllegalStateException("事件负载缺少可解析的 accountId id=" + entry.getId());
        }
        JsonNode version = node.get("securityVersion");
        long securityVersion = version != null && version.canConvertToLong() ? version.asLong() : 0L;
        return new Payload(accountId.asLong(), securityVersion);
    }

    /**
     * 记录一次投递失败。
     *
     * @return true 表示已判死（不再重试），false 表示仍在退避重试
     */
    private boolean recordFailure(SecurityEventOutboxEntry entry, RuntimeException ex) {
        int attempts = (entry.getAttempts() == null ? 0 : entry.getAttempts()) + 1;
        boolean dead = attempts >= MAX_ATTEMPTS;
        String error = truncateError(ex);
        long backoffMs = dead ? 0L : backoffMillis(attempts);
        try {
            outboxMapper.markAttemptFailed(entry.getId(),
                    dead ? SecurityEventOutboxEntry.STATUS_DEAD : SecurityEventOutboxEntry.STATUS_FAILED,
                    error, backoffMs);
        } catch (RuntimeException updateFailure) {
            // 连"记录失败"都失败（数据库不可用）：不抛出，否则会中断本批其余事件。
            // 该行仍是 PENDING/FAILED，下一轮会重新领取。
            log.warn("[Security] 记录 outbox 投递失败时又失败 id={}: {}",
                    entry.getId(), updateFailure.getMessage());
            return false;
        }
        if (dead) {
            log.error("[Security] outbox 事件已判死（重试 {} 次仍失败）id={} type={} aggregate={} 原因={}",
                    attempts, entry.getId(), entry.getEventType(), entry.getAggregateId(), error);
        } else {
            log.warn("[Security] outbox 投递失败 id={} 第 {} 次，{} ms 后重试：{}",
                    entry.getId(), attempts, backoffMs, error);
        }
        return dead;
    }

    /** 指数退避：1s, 2s, 4s … 封顶 {@link #MAX_BACKOFF_MS}。 */
    private long backoffMillis(int attempts) {
        int shift = Math.min(attempts - 1, 6);
        long backoff = BASE_BACKOFF_MS << shift;
        return Math.min(backoff, MAX_BACKOFF_MS);
    }

    private String truncateError(RuntimeException ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }

    /** 投递所需的负载字段。 */
    private record Payload(long accountId, long securityVersion) {
    }
}
