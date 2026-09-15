package com.xander.lab.identity.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.common.security.ConnectionRegistry;
import com.xander.lab.common.security.SecurityStateCache;
import com.xander.lab.identity.domain.SecurityEventOutboxEntry;
import com.xander.lab.identity.infrastructure.SecurityEventOutboxMapper;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 安全事件发件箱投递器测试（V9 §27 / §28.3 / §68）。
 *
 * <p>投递目标用<b>真实</b>的 {@link SecurityStateApplier} + {@link SecurityStateCache} +
 * {@link ConnectionRegistry}：本类的职责是"把事件真正作用到本地安全平面"，
 * 把 applier mock 掉就等于只测了循环结构，测不到"事件到了但缓存没变"。</p>
 */
@ExtendWith(MockitoExtension.class)
class SecurityEventOutboxPublisherTest {

    private static final long ACCOUNT = 42L;
    private static final int BATCH = 50;

    @Mock
    private SecurityEventOutboxMapper outboxMapper;

    private SecurityStateCache cache;
    private ConnectionRegistry registry;
    private RefreshTokenProperties properties;
    private SecurityEventOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        cache = new SecurityStateCache();
        registry = new ConnectionRegistry();
        properties = new RefreshTokenProperties();
        properties.setSecurityOutboxPublishBatchSize(BATCH);
        publisher = new SecurityEventOutboxPublisher(
                outboxMapper,
                new SecurityStateApplier(cache, registry),
                properties,
                new ObjectMapper());
    }

    @Test
    void publishesAccountDisabledEventToLocalSecurityPlane() {
        RecordingHandle handle = new RecordingHandle(ACCOUNT, ConnectionRegistry.KIND_SSE);
        registry.register(handle);
        when(outboxMapper.claimDue(BATCH))
                .thenReturn(List.of(event(1L, SecurityEventOutboxEntry.TYPE_ACCOUNT_DISABLED, ACCOUNT, 0)));

        publisher.publishDueEvents();

        assertThat(cache.isDisabled(ACCOUNT)).isTrue();
        assertThat(cache.versionOf(ACCOUNT)).hasValue(7L);
        assertThat(handle.closeReason).isEqualTo(ConnectionRegistry.REASON_ACCOUNT_DISABLED);
        verify(outboxMapper).markPublished(1L);
    }

    @Test
    void publishesAccountEnabledEventWithoutClosingConnections() {
        RecordingHandle handle = new RecordingHandle(ACCOUNT, ConnectionRegistry.KIND_SSE);
        registry.register(handle);
        cache.markDisabled(ACCOUNT, 1L);
        when(outboxMapper.claimDue(BATCH))
                .thenReturn(List.of(event(2L, SecurityEventOutboxEntry.TYPE_ACCOUNT_ENABLED, ACCOUNT, 0)));

        publisher.publishDueEvents();

        assertThat(cache.isDisabled(ACCOUNT)).isFalse();
        assertThat(handle.closeCount).isZero();
        verify(outboxMapper).markPublished(2L);
    }

    @Test
    void batchSizeComesFromConfiguration() {
        when(outboxMapper.claimDue(BATCH)).thenReturn(List.of());

        publisher.publishDueEvents();

        verify(outboxMapper).claimDue(BATCH);
    }

    /**
     * 本类是"投递者"，不是"对账者"：它<b>不得</b>推进 reconciler 的游标。
     *
     * <p>发件箱行的可见性取决于事务提交顺序，而 {@code change_seq} 在事务内分配 ——
     * 两者顺序可以不一致。若投递成功就把游标推到该事件的 changeSeq，
     * 一条"后提交但序号更小"的变更会被 {@code pullChangesAfter} 永久跳过，
     * 那个账号的封禁永远不会到达本实例。这是本类最危险的一个"看起来更合理"的改法。</p>
     */
    @Test
    void neverAdvancesTheReconcilerCursor() {
        cache.advanceCursor(11L);
        when(outboxMapper.claimDue(BATCH))
                .thenReturn(List.of(event(20L, SecurityEventOutboxEntry.TYPE_ACCOUNT_DISABLED, ACCOUNT, 0)));

        publisher.publishDueEvents();

        assertThat(cache.lastSeenSeq()).as("游标只归 SecurityStateReconciler 所有").isEqualTo(11L);
    }

    /**
     * 同理，本类不得伪报"已同步"：§87 的 {@code securityStateAge} 可能被用于
     * fail-closed 判定，把它刷新成"刚刚同步过"会掩盖一个已经停摆的 reconciler。
     */
    @Test
    void neverFakesTheSyncTimestamp() {
        when(outboxMapper.claimDue(BATCH))
                .thenReturn(List.of(event(1L, SecurityEventOutboxEntry.TYPE_ACCOUNT_DISABLED, ACCOUNT, 0)));

        publisher.publishDueEvents();

        assertThat(cache.lastSuccessfulSyncAt()).isZero();
    }

    @Test
    void oneBadEventDoesNotBlockTheRestOfTheBatch() {
        SecurityEventOutboxEntry bad = event(1L, SecurityEventOutboxEntry.TYPE_ACCOUNT_DISABLED, ACCOUNT, 0);
        bad.setPayload(null);
        when(outboxMapper.claimDue(BATCH))
                .thenReturn(List.of(bad, event(2L, SecurityEventOutboxEntry.TYPE_ACCOUNT_DISABLED, ACCOUNT + 1, 0)));

        publisher.publishDueEvents();

        assertThat(cache.isDisabled(ACCOUNT + 1)).as("后面的好事件必须照常投递").isTrue();
        verify(outboxMapper).markPublished(2L);
        verify(outboxMapper).markAttemptFailed(eq(1L),
                eq(SecurityEventOutboxEntry.STATUS_FAILED), anyString(), eq(1_000L));
    }

    @Test
    void malformedPayloadIsRetriedWithBaseBackoff() {
        SecurityEventOutboxEntry broken = event(9L, SecurityEventOutboxEntry.TYPE_ACCOUNT_DISABLED, ACCOUNT, 0);
        broken.setPayload("{\"accountId\":");
        when(outboxMapper.claimDue(BATCH)).thenReturn(List.of(broken));

        publisher.publishDueEvents();

        assertThat(cache.isDisabled(ACCOUNT)).as("负载不可信时不得改动缓存").isFalse();
        verify(outboxMapper).markAttemptFailed(eq(9L),
                eq(SecurityEventOutboxEntry.STATUS_FAILED), anyString(), eq(1_000L));
    }

    @Test
    void payloadMissingAccountIdIsRecordedAsFailure() {
        SecurityEventOutboxEntry broken = event(9L, SecurityEventOutboxEntry.TYPE_ACCOUNT_DISABLED, ACCOUNT, 0);
        broken.setPayload("{\"securityVersion\":7}");
        when(outboxMapper.claimDue(BATCH)).thenReturn(List.of(broken));

        publisher.publishDueEvents();

        verify(outboxMapper).markAttemptFailed(eq(9L),
                eq(SecurityEventOutboxEntry.STATUS_FAILED), anyString(), anyLong());
    }

    /** 负载与 aggregate_id 指向不同账号说明写入方有 bug；按哪个 id 执行都是错的，必须留痕。 */
    @Test
    void payloadAccountIdDisagreeingWithAggregateIdIsRecordedAsFailure() {
        SecurityEventOutboxEntry inconsistent = event(9L, SecurityEventOutboxEntry.TYPE_ACCOUNT_DISABLED, ACCOUNT, 0);
        inconsistent.setAggregateId(String.valueOf(ACCOUNT + 1));
        when(outboxMapper.claimDue(BATCH)).thenReturn(List.of(inconsistent));

        publisher.publishDueEvents();

        assertThat(cache.isDisabled(ACCOUNT)).isFalse();
        assertThat(cache.isDisabled(ACCOUNT + 1)).isFalse();
        verify(outboxMapper).markAttemptFailed(eq(9L),
                eq(SecurityEventOutboxEntry.STATUS_FAILED), anyString(), anyLong());
    }

    @Test
    void unknownEventTypeIsRecordedAsFailure() {
        SecurityEventOutboxEntry unknown = event(3L, "SomethingFromTheFuture", ACCOUNT, 0);
        when(outboxMapper.claimDue(BATCH)).thenReturn(List.of(unknown));

        publisher.publishDueEvents();

        verify(outboxMapper).markAttemptFailed(eq(3L),
                eq(SecurityEventOutboxEntry.STATUS_FAILED), anyString(), eq(1_000L));
    }

    /**
     * RefreshTokenReuse 必须在解析负载<b>之前</b>就被确认掉。
     *
     * <p>家族撤销已经在检测到重用的写事务里完成，本地安全平面无需动作；
     * 而这条事件的负载格式与账号事件不同（没有 accountId），若落到"解析负载"那条路上，
     * 一条本应无害的事件会被判成畸形并最终判死。</p>
     */
    @Test
    void refreshTokenReuseEventIsAcknowledgedWithoutTouchingLocalState() {
        SecurityEventOutboxEntry reuse = event(4L, SecurityEventOutboxEntry.TYPE_REFRESH_TOKEN_REUSE, ACCOUNT, 0);
        reuse.setPayload(null);
        when(outboxMapper.claimDue(BATCH)).thenReturn(List.of(reuse));

        publisher.publishDueEvents();

        assertThat(cache.isDisabled(ACCOUNT)).isFalse();
        verify(outboxMapper).markPublished(4L);
    }

    @Test
    void backoffGrowsExponentiallyAndCapsAtOneMinute() {
        // attempts 是"已经失败过的次数"，本次失败后是 attempts + 1
        assertBackoffForAttempts(0, 1_000L);
        assertBackoffForAttempts(1, 2_000L);
        assertBackoffForAttempts(2, 4_000L);
        assertBackoffForAttempts(5, 32_000L);
        // 6 次之后本次为第 7 次：2^6 秒 = 64 秒，被 MAX_BACKOFF_MS 截到 60 秒，且仍可重试
        assertBackoffForAttempts(6, 60_000L);
    }

    /**
     * 退避封顶与判死阈值的交界必须精确。
     *
     * <p>{@code MAX_ATTEMPTS = 8} 意味着第 8 次失败即判死，所以"最后一个还会退避重试的
     * 尝试"是第 7 次（{@code attempts=6}）。这个边界很容易被一次退避公式改动悄悄挪掉：
     * 挪早了会让事件过早判死（快路径永久失效，只剩兜底），挪晚了会让 DEAD 永远不触发
     * （退避封顶后无限重试）。两种偏差都不会有任何报错。</p>
     */
    @Test
    void retryStopsExactlyAtTheDeathThreshold() {
        assertBackoffForAttempts(6, 60_000L);                       // 第 7 次：最后一次退避
        assertParkedAsDead(7);                                       // 第 8 次：判死
    }

    @Test
    void eventIsParkedAsDeadAfterMaxAttempts() {
        assertParkedAsDead(SecurityEventOutboxPublisher.MAX_ATTEMPTS - 1);
    }

    @Test
    void longErrorMessagesAreTruncatedToColumnWidth() {
        SecurityEventOutboxEntry verbose = event(5L, "X".repeat(600), ACCOUNT, 0);
        when(outboxMapper.claimDue(BATCH)).thenReturn(List.of(verbose));

        publisher.publishDueEvents();

        verify(outboxMapper).markAttemptFailed(eq(5L), eq(SecurityEventOutboxEntry.STATUS_FAILED),
                argThat(message -> message != null && message.length() <= 480), anyLong());
    }

    /**
     * "记录失败"本身失败时不能抛出：否则一条事件的失败会中断整批，
     * 后面本来能投递的事件全部被推迟到下一轮。
     */
    @Test
    void databaseFailureWhileRecordingDoesNotAbortTheBatch() {
        SecurityEventOutboxEntry bad = event(1L, SecurityEventOutboxEntry.TYPE_ACCOUNT_DISABLED, ACCOUNT, 0);
        bad.setPayload(null);
        when(outboxMapper.claimDue(BATCH))
                .thenReturn(List.of(bad, event(2L, SecurityEventOutboxEntry.TYPE_ACCOUNT_DISABLED, ACCOUNT + 1, 0)));
        when(outboxMapper.markAttemptFailed(eq(1L), anyString(), anyString(), anyLong()))
                .thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> publisher.publishDueEvents()).doesNotThrowAnyException();

        assertThat(cache.isDisabled(ACCOUNT + 1)).as("后续事件仍须被处理").isTrue();
    }

    @Test
    void claimFailureDoesNotEscapeTheScheduler() {
        when(outboxMapper.claimDue(BATCH)).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> publisher.publishDueEvents()).doesNotThrowAnyException();
    }

    @Test
    void emptyBatchIsANoOp() {
        when(outboxMapper.claimDue(BATCH)).thenReturn(List.of());

        publisher.publishDueEvents();

        verify(outboxMapper, never()).markPublished(anyLong());
        verify(outboxMapper, never()).markAttemptFailed(anyLong(), anyString(), anyString(), anyLong());
    }

    /**
     * 注解守卫（V9 §68）。
     *
     * <p>{@code execution_scope = global_once} 的落地形式就是"带 {@code @SchedulerLock}"。
     * 单测跑不到 ShedLock 切面，所以一旦有人把注解删掉，除了多实例下重复投递之外
     * <b>没有任何症状</b> —— 必须显式守。锁名同时是
     * {@code docs/scheduled-ownership.yaml} 里的 id，改一处必须改两处。</p>
     */
    @Test
    void publishDueEventsIsGloballyLocked() throws NoSuchMethodException {
        Method method = SecurityEventOutboxPublisher.class.getMethod("publishDueEvents");

        assertThat(method.getAnnotation(Scheduled.class))
                .as("@Scheduled 缺失则整个投递器不会运行")
                .isNotNull();
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);
        assertThat(lock).as("global_once 任务必须带 @SchedulerLock（V9 §68）").isNotNull();
        assertThat(lock.name())
                .as("锁名必须等于 scheduled-ownership.yaml 里的 id")
                .isEqualTo("security-event-outbox-publisher");
    }

    /** 断言"再失败一次即判死"，且判死时不再安排退避。 */
    private void assertParkedAsDead(int alreadyFailedAttempts) {
        SecurityEventOutboxEntry poisoned = event(9L, SecurityEventOutboxEntry.TYPE_ACCOUNT_DISABLED, ACCOUNT,
                alreadyFailedAttempts);
        poisoned.setPayload("not json");
        when(outboxMapper.claimDue(BATCH)).thenReturn(List.of(poisoned));
        clearInvocations(outboxMapper);

        publisher.publishDueEvents();

        // 判死时不再安排退避：DEAD 不在 claimDue 的领取集合里，退避值无意义，传 0
        verify(outboxMapper).markAttemptFailed(eq(9L),
                eq(SecurityEventOutboxEntry.STATUS_DEAD), anyString(), eq(0L));
    }

    private void assertBackoffForAttempts(int alreadyFailedAttempts, long expectedBackoffMs) {
        SecurityEventOutboxEntry broken = event(9L, SecurityEventOutboxEntry.TYPE_ACCOUNT_DISABLED, ACCOUNT,
                alreadyFailedAttempts);
        broken.setPayload(null);
        when(outboxMapper.claimDue(BATCH)).thenReturn(List.of(broken));
        // 每轮先清掉历史调用：否则退避封顶后两轮会产生两条同参数调用，verify 的"恰好一次"会失败
        clearInvocations(outboxMapper);

        publisher.publishDueEvents();

        verify(outboxMapper).markAttemptFailed(eq(9L), anyString(), anyString(), eq(expectedBackoffMs));
    }

    private SecurityEventOutboxEntry event(long id, String eventType, long accountId, int attempts) {
        SecurityEventOutboxEntry entry = new SecurityEventOutboxEntry();
        entry.setId(id);
        entry.setAggregateType("ACCOUNT");
        entry.setAggregateId(String.valueOf(accountId));
        entry.setEventType(eventType);
        // 与 AccountSecurityStateService.writeOutboxEvent 写出的结构保持一致
        entry.setPayload("{\"accountId\":" + accountId
                + ",\"securityVersion\":7"
                + ",\"changeSeq\":" + id
                + ",\"reason\":\"admin-ban operator=1\""
                + ",\"occurredAt\":\"2026-09-15T16:00\"}");
        entry.setStatus(SecurityEventOutboxEntry.STATUS_PENDING);
        entry.setAttempts(attempts);
        return entry;
    }

    /** 可记录关闭原因与次数的连接句柄。 */
    private static final class RecordingHandle implements ConnectionRegistry.ConnectionHandle {

        private final Long accountId;
        private final String kind;
        private String closeReason;
        private int closeCount;

        private RecordingHandle(Long accountId, String kind) {
            this.accountId = accountId;
            this.kind = kind;
        }

        @Override
        public Long accountId() {
            return accountId;
        }

        @Override
        public String kind() {
            return kind;
        }

        @Override
        public void close(String reason) {
            this.closeReason = reason;
            this.closeCount++;
        }
    }
}
