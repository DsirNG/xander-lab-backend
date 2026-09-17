package com.xander.lab.identity.application;

import com.xander.lab.common.security.ConnectionRegistry;
import com.xander.lab.common.security.SecurityPlaneReadiness;
import com.xander.lab.common.security.SecurityStateCache;
import com.xander.lab.identity.domain.AccountSecurityState;
import com.xander.lab.identity.infrastructure.AccountSecurityStateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 安全状态对账的就绪闸门测试（V11 §27.1）。
 *
 * <p>本类只钉一件事：<b>安全平面 READY 之前，两个调度方法必须什么都不做</b>。
 * 启动期的缓存归 {@link SecurityStateBootstrap} 独占 —— 调度器在容器 refresh 期间
 * 就已启动，可能早于 {@code ApplicationRunner} 执行，并发时 {@code advanceCursor}
 * 会把游标推到 bootstrap 尚未覆盖的位置，使"先快照后重放"这个顺序失效。</p>
 *
 * <p>⚠️ 删掉那两个 {@code if (!readiness.isReady()) return;} 之后，本类的
 * {@code *DoesNothingBeforeThePlaneIsReady} 两个用例是<b>唯一</b>会变红的测试 ——
 * 也就是说这条约束没有别的症状。这正是它需要被钉住的原因。</p>
 */
class SecurityStateReconcilerTest {

    private static final long ACCOUNT = 42L;

    private AccountSecurityStateMapper mapper;
    private SecurityStateCache cache;
    private SecurityPlaneReadiness readiness;
    private RefreshTokenProperties properties;
    private SecurityStateReconciler reconciler;

    @BeforeEach
    void setUp() {
        mapper = mock(AccountSecurityStateMapper.class);
        cache = new SecurityStateCache();
        readiness = new SecurityPlaneReadiness();
        properties = new RefreshTokenProperties();
        SecurityStateApplier applier = new SecurityStateApplier(cache, new ConnectionRegistry());
        reconciler = new SecurityStateReconciler(mapper, cache, applier, properties, readiness);
    }

    // ------------------------------------------------------------------
    // 就绪闸门
    // ------------------------------------------------------------------

    @Test
    void reconcileDoesNothingBeforeThePlaneIsReady() {
        when(mapper.pullChangesAfter(anyLong(), anyInt()))
                .thenReturn(List.of(row(ACCOUNT, true, 1L, 5L)));

        reconciler.reconcile();

        verify(mapper, never()).pullChangesAfter(anyLong(), anyInt());
        assertThat(cache.size()).isZero();
        assertThat(cache.lastSeenSeq()).as("游标不得被启动期的调度器推进").isZero();
        assertThat(cache.lastSuccessfulSyncAt())
                .as("也不得伪造一次同步 —— 那会掩盖 bootstrap 尚未完成这件事")
                .isZero();
    }

    @Test
    void fullReconcileDoesNothingBeforeThePlaneIsReady() {
        when(mapper.listDisabled()).thenReturn(List.of(row(ACCOUNT, true, 1L, 5L)));

        reconciler.fullReconcile();

        verify(mapper, never()).listDisabled();
        assertThat(cache.size()).isZero();
        assertThat(cache.lastSuccessfulSyncAt()).isZero();
    }

    // ------------------------------------------------------------------
    // READY 之后的正常路径（确认闸门不是把功能整个关掉）
    // ------------------------------------------------------------------

    @Test
    void reconcileAppliesChangesOnceThePlaneIsReady() {
        readiness.markReady(1L);
        when(mapper.pullChangesAfter(0L, properties.getSecurityReconcileBatchSize()))
                .thenReturn(List.of(row(ACCOUNT, true, 7L, 5L)));

        reconciler.reconcile();

        assertThat(cache.isDisabled(ACCOUNT)).isTrue();
        assertThat(cache.versionOf(ACCOUNT)).hasValue(7L);
        assertThat(cache.lastSeenSeq()).isEqualTo(5L);
        assertThat(cache.lastSuccessfulSyncAt()).isPositive();
    }

    @Test
    void fullReconcileReplacesTheDenySetOnceThePlaneIsReady() {
        readiness.markReady(1L);
        when(mapper.listDisabled()).thenReturn(List.of(row(ACCOUNT, true, 3L, 9L)));

        reconciler.fullReconcile();

        assertThat(cache.snapshot()).containsExactly(ACCOUNT);
        assertThat(cache.versionOf(ACCOUNT)).hasValue(3L);
        assertThat(cache.lastSuccessfulSyncAt()).isPositive();
    }

    @Test
    void aFailedRoundDoesNotThrowSoTheScheduleKeepsRunning() {
        readiness.markReady(1L);
        when(mapper.pullChangesAfter(anyLong(), anyInt()))
                .thenThrow(new RuntimeException("数据库抖动"));

        assertThatCode(reconciler::reconcile).doesNotThrowAnyException();

        assertThat(cache.lastSeenSeq())
                .as("失败轮次不得推进游标，下一轮会重试同一区间（幂等）")
                .isZero();
        assertThat(cache.lastSuccessfulSyncAt()).isZero();
    }

    private static AccountSecurityState row(long accountId, boolean disabled, long version, Long changeSeq) {
        AccountSecurityState state = new AccountSecurityState();
        state.setAccountId(accountId);
        state.setDisabled(disabled ? 1 : 0);
        state.setSecurityVersion(version);
        state.setChangeSeq(changeSeq);
        return state;
    }
}
