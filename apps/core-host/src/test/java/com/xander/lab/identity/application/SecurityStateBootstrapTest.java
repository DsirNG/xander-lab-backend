package com.xander.lab.identity.application;

import com.xander.lab.common.security.ConnectionRegistry;
import com.xander.lab.common.security.SecurityPlaneReadiness;
import com.xander.lab.common.security.SecurityStateCache;
import com.xander.lab.identity.domain.AccountSecurityState;
import com.xander.lab.identity.infrastructure.AccountSecurityStateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 实例启动 Bootstrap 测试（V11 §27.1 九步）。
 *
 * <p>这里用<b>真实</b>的 {@link SecurityStateCache} / {@link SecurityStateApplier} /
 * {@link SecurityPlaneReadiness}，只把 {@link AccountSecurityStateMapper} 换成桩：
 * 本类要证明的是"快照与重放合起来不漏封禁"这个<b>时序</b>性质，mock 掉缓存之后
 * 就只能断言"调用过某个方法"，而无法发现"快照覆盖了重放"或"重放区间取错下界"
 * 这类真实缺陷。</p>
 *
 * <p>mapper 桩刻意按真实 SQL 的语义实现（{@code listDisabled} = 取
 * {@code disabled = 1}；{@code pullChangesAfter} = 取 {@code change_seq > cursor}
 * 升序截断），并且区分"快照看到的那一版表"与"重放看到的那一版表" ——
 * 这个区分正是 §27.1 要修的场景（快照期间发生的新封禁）。</p>
 */
class SecurityStateBootstrapTest {

    private static final long ACCOUNT_A = 101L;
    private static final long ACCOUNT_B = 202L;
    private static final long ACCOUNT_C = 303L;

    private AccountSecurityStateMapper mapper;
    private SecurityStateCache cache;
    private SecurityStateApplier applier;
    private SecurityPlaneReadiness readiness;
    private RefreshTokenProperties properties;
    private SecurityStateBootstrap bootstrap;
    private ApplicationArguments arguments;

    /** {@code listDisabled()} 看到的那一版表。 */
    private List<AccountSecurityState> snapshotView = List.of();

    /** {@code pullChangesAfter()} 看到的那一版表；默认与快照一致。 */
    private List<AccountSecurityState> replayView = List.of();

    @BeforeEach
    void setUp() {
        mapper = mock(AccountSecurityStateMapper.class);
        cache = new SecurityStateCache();
        applier = new SecurityStateApplier(cache, new ConnectionRegistry());
        readiness = new SecurityPlaneReadiness();
        properties = new RefreshTokenProperties();
        bootstrap = new SecurityStateBootstrap(mapper, applier, cache, readiness, properties);
        arguments = mock(ApplicationArguments.class);

        when(mapper.listDisabled()).thenAnswer(invocation -> snapshotView);
        when(mapper.pullChangesAfter(anyLong(), anyInt())).thenAnswer(invocation -> {
            long cursor = invocation.getArgument(0);
            int limit = invocation.getArgument(1);
            return replayView.stream()
                    .filter(row -> row.getChangeSeq() != null && row.getChangeSeq() > cursor)
                    .sorted(Comparator.comparingLong(AccountSecurityState::getChangeSeq))
                    .limit(limit)
                    .toList();
        });
    }

    // ------------------------------------------------------------------
    // 第 2 / 3 / 4 步：快照
    // ------------------------------------------------------------------

    @Test
    void snapshotPopulatesTheDenySetAndMarksThePlaneReady() {
        givenTable(row(ACCOUNT_A, true, 2L, 3L), row(ACCOUNT_B, true, 5L, 7L));

        bootstrap.run(arguments);

        assertThat(cache.isDisabled(ACCOUNT_A)).isTrue();
        assertThat(cache.versionOf(ACCOUNT_A)).hasValue(2L);
        assertThat(cache.isDisabled(ACCOUNT_B)).isTrue();
        assertThat(cache.versionOf(ACCOUNT_B)).hasValue(5L);
        assertThat(cache.lastSeenSeq()).as("第 7 步：游标推进到快照水位").isEqualTo(7L);
        assertThat(readiness.isReady()).as("第 8 步").isTrue();
        assertThat(readiness.failureReason()).isNull();
    }

    @Test
    void aBanCommittedAfterTheSnapshotReadIsReplayed() {
        // 快照只看得见 A（水位 3）；C 的封禁在快照读之后才提交（change_seq = 9）。
        // 这正是"snapshot 期间发生新封禁"的场景：没有 replay 就会漏掉 C。
        snapshotView = List.of(row(ACCOUNT_A, true, 2L, 3L));
        replayView = List.of(row(ACCOUNT_A, true, 2L, 3L), row(ACCOUNT_C, true, 1L, 9L));

        bootstrap.run(arguments);

        assertThat(cache.isDisabled(ACCOUNT_C))
                .as("快照之后提交的封禁必须被 replay 补上（V11 §27.1 第 5 步）")
                .isTrue();
        assertThat(cache.versionOf(ACCOUNT_C)).hasValue(1L);
        assertThat(cache.lastSeenSeq()).isEqualTo(9L);
    }

    @Test
    void replayAlsoAppliesLiftedBansSoAStaleDisableDoesNotSurviveARestart() {
        snapshotView = List.of(row(ACCOUNT_A, true, 1L, 3L));
        replayView = List.of(row(ACCOUNT_A, false, 2L, 9L));

        bootstrap.run(arguments);

        assertThat(cache.isDisabled(ACCOUNT_A))
                .as("快照之后解封的账号不得在重启后仍被本地缓存拦住")
                .isFalse();
        assertThat(cache.lastSeenSeq()).isEqualTo(9L);
    }

    // ------------------------------------------------------------------
    // 第 2 步：水位（E0）取在哪
    // ------------------------------------------------------------------

    @Test
    void theReplayFloorIsTheSnapshotWatermarkNotTheTablesMaximum() {
        givenTable(row(ACCOUNT_A, true, 2L, 3L), row(ACCOUNT_B, true, 5L, 7L));

        bootstrap.run(arguments);

        // 第一次拉取必须以快照水位为下界，而不是 0，也不是"表里最大的 change_seq"
        // （本仓库里两者相等，但下界的语义来源必须是快照本身）。
        verify(mapper).pullChangesAfter(7L, 500);
    }

    @Test
    void anEmptySnapshotStillReplaysFromZeroSoNoChangeCanBeSkipped() {
        // 表里一个封禁都没有：水位只能是 0，于是 replay 从 0 开始 ——
        // 下界取小只会多扫，取大才会漏封禁，所以这里必须偏小。
        snapshotView = List.of();
        replayView = List.of(row(ACCOUNT_A, true, 1L, 4L));

        bootstrap.run(arguments);

        verify(mapper).pullChangesAfter(0L, 500);
        assertThat(cache.isDisabled(ACCOUNT_A)).isTrue();
        assertThat(cache.lastSeenSeq()).isEqualTo(4L);
    }

    @Test
    void theWatermarkIsDerivedFromTheSameReadAsTheSnapshot() {
        // §27.1 要求 snapshot 与 watermark 取自同一一致性点。本实现的强制方式是
        // "只有一条查询"：除 listDisabled 与按游标的增量拉取之外，不得再有别的取数查询，
        // 否则两次读之间的提交就会落进缝里。
        givenTable(row(ACCOUNT_A, true, 1L, 3L));

        bootstrap.run(arguments);

        verify(mapper, times(1)).listDisabled();
        verify(mapper).pullChangesAfter(3L, 500);
        // 水位不是另发一条查询取来的（那会引入第二个一致性点），也不是从序列表读来的。
        verify(mapper, never()).currentChangeSeq();
        verify(mapper, never()).allocateChangeSeq();
    }

    // ------------------------------------------------------------------
    // 第 5 / 7 步：分页 replay
    // ------------------------------------------------------------------

    @Test
    void replayIsPagedAndAFullBatchDoesNotTerminateTheLoop() {
        properties.setSecurityReconcileBatchSize(2);
        snapshotView = List.of();
        replayView = List.of(row(ACCOUNT_A, true, 1L, 1L),
                row(ACCOUNT_B, true, 1L, 2L),
                row(ACCOUNT_C, true, 1L, 3L));

        bootstrap.run(arguments);

        assertThat(cache.snapshot()).containsExactlyInAnyOrder(ACCOUNT_A, ACCOUNT_B, ACCOUNT_C);
        assertThat(cache.lastSeenSeq()).isEqualTo(3L);
        // 第一批正好装满 2 条（1、2），但表里还有第 3 条：循环必须继续 ——
        // 第二次调用的游标是 2 而不是回到 0，也不是就此结束。
        // 第二批只回来 1 条（不满批），说明已经没有更多行，循环在这里结束。
        verify(mapper).pullChangesAfter(0L, 2);
        verify(mapper).pullChangesAfter(2L, 2);
        verify(mapper, times(2)).pullChangesAfter(anyLong(), anyInt());
    }

    @Test
    void anExactlyFullFinalBatchIsFollowedByOneEmptyPull() {
        // 满批不等于"没有更多"，所以即使最后一批正好装满也必须再拉一次确认。
        // 这一次确认是循环真正的终止条件（空批），而不是"批没满"这个快捷判断。
        properties.setSecurityReconcileBatchSize(2);
        snapshotView = List.of();
        replayView = List.of(row(ACCOUNT_A, true, 1L, 1L), row(ACCOUNT_B, true, 1L, 2L));

        bootstrap.run(arguments);

        assertThat(cache.snapshot()).containsExactlyInAnyOrder(ACCOUNT_A, ACCOUNT_B);
        verify(mapper).pullChangesAfter(0L, 2);
        verify(mapper).pullChangesAfter(2L, 2);
        verify(mapper, times(2)).pullChangesAfter(anyLong(), anyInt());
    }

    @Test
    void aPullThatCannotAdvanceTheCursorFailsInsteadOfLoopingForever() {
        // change_seq 在当前 DDL 下是 NOT NULL，所以这条路径不可达；但"拉回行却推不动游标"
        // 会让启动挂死，是一个不会报错的失败。这里把它钉成 fail-fast。
        properties.setSecurityReconcileBatchSize(1);
        AccountSecurityState noCursor = row(ACCOUNT_A, true, 1L, null);
        when(mapper.pullChangesAfter(anyLong(), anyInt())).thenReturn(List.of(noCursor));

        assertThatThrownBy(() -> bootstrap.run(arguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("change_seq");

        verify(mapper, times(1)).pullChangesAfter(anyLong(), anyInt());
        assertThat(readiness.isReady()).isFalse();
    }

    // ------------------------------------------------------------------
    // 第 6 步：版本单调（纵深防御）
    // ------------------------------------------------------------------

    @Test
    void aReplayWithAnOlderVersionCannotRegressTheCachedVersion() {
        // 写入方保证 security_version 单账号单调，所以"重放到更旧的版本"在真实 SQL 下
        // 不可达。这条断言钉的是 §27.1 第 6 步的闸门本身：一旦将来写入路径改成
        // 读-改-写（而不是 SQL 侧 +1），它必须仍然拦得住。
        snapshotView = List.of(row(ACCOUNT_A, true, 5L, 3L));
        replayView = List.of(row(ACCOUNT_A, true, 3L, 9L));

        bootstrap.run(arguments);

        assertThat(cache.versionOf(ACCOUNT_A))
                .as("迟到的旧版本不得把缓存里的版本号改回去")
                .hasValue(5L);
    }

    // ------------------------------------------------------------------
    // 第 1 / 8 步：就绪门禁
    // ------------------------------------------------------------------

    @Test
    void thePlaneIsNotReadyWhileTheSnapshotIsStillBeingLoaded() {
        List<Boolean> readyDuringSnapshot = new ArrayList<>();
        doAnswer(invocation -> {
            readyDuringSnapshot.add(readiness.isReady());
            return snapshotView;
        }).when(mapper).listDisabled();
        givenTable(row(ACCOUNT_A, true, 1L, 3L));

        bootstrap.run(arguments);

        assertThat(readyDuringSnapshot)
                .as("§27.1 第 1 步：读快照时实例必须还是 NOT_READY")
                .containsExactly(false);
    }

    @Test
    void thePlaneIsNotReadyUntilTheReplayHasDrained() {
        properties.setSecurityReconcileBatchSize(2);
        snapshotView = List.of();
        replayView = List.of(row(ACCOUNT_A, true, 1L, 1L), row(ACCOUNT_B, true, 1L, 2L));

        List<Boolean> readyDuringReplay = new ArrayList<>();
        when(mapper.pullChangesAfter(anyLong(), anyInt())).thenAnswer(invocation -> {
            long cursor = invocation.getArgument(0);
            int limit = invocation.getArgument(1);
            readyDuringReplay.add(readiness.isReady());
            return replayView.stream()
                    .filter(row -> row.getChangeSeq() != null && row.getChangeSeq() > cursor)
                    .sorted(Comparator.comparingLong(AccountSecurityState::getChangeSeq))
                    .limit(limit)
                    .toList();
        });

        bootstrap.run(arguments);

        assertThat(readyDuringReplay)
                .as("第 8 步必须在 replay 结束之后，而不是快照之后")
                .containsExactly(false, false);
        assertThat(readiness.isReady()).isTrue();
    }

    @Test
    void thePlaneIsMarkedSyncedSoStaleDetectionDoesNotLockOutAFreshInstance() {
        givenTable(row(ACCOUNT_A, true, 1L, 3L));

        bootstrap.run(arguments);

        long now = System.currentTimeMillis();
        assertThat(cache.lastSuccessfulSyncAt()).as("markSynced 必须被调用").isPositive();
        assertThat(cache.ageMillis(now))
                .as("未 markSynced 时 ageMillis 返回 Long.MAX_VALUE，§28.2 的 stale 判定会把刚启动的实例直接封锁")
                .isLessThan(1_000L);
    }

    // ------------------------------------------------------------------
    // 失败路径：fail-fast
    // ------------------------------------------------------------------

    @Test
    void aSnapshotFailureRefusesToStartInsteadOfServingAnEmptyDenyCache() {
        when(mapper.listDisabled()).thenThrow(new RuntimeException("连接池耗尽"));

        assertThatThrownBy(() -> bootstrap.run(arguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("§27.1")
                .hasMessageContaining("连接池耗尽");

        assertThat(readiness.isReady()).isFalse();
        assertThat(readiness.state()).isEqualTo(SecurityPlaneReadiness.STATE_NOT_READY);
        assertThat(readiness.failureReason()).contains("连接池耗尽");
        assertThat(cache.size()).isZero();
        assertThat(cache.lastSuccessfulSyncAt()).isZero();
    }

    @Test
    void aReplayFailureRefusesToStartToo() {
        snapshotView = List.of(row(ACCOUNT_A, true, 1L, 3L));
        when(mapper.pullChangesAfter(anyLong(), anyInt()))
                .thenThrow(new RuntimeException("读副本不可用"));

        assertThatThrownBy(() -> bootstrap.run(arguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("读副本不可用");

        assertThat(readiness.isReady()).isFalse();
        assertThat(readiness.failureReason()).contains("读副本不可用");
        // 快照已经落进缓存，但实例仍然拒绝服务：缓存"部分完整"不能作为就绪的依据。
        assertThat(cache.isDisabled(ACCOUNT_A)).isTrue();
        assertThat(cache.lastSuccessfulSyncAt()).as("失败不得留下成功的同步时间").isZero();
    }

    // ------------------------------------------------------------------

    /** 快照与重放看到同一版表。 */
    private void givenTable(AccountSecurityState... rows) {
        snapshotView = List.of(rows);
        replayView = List.of(rows);
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
