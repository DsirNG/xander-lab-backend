package com.xander.lab.identity.application;

import com.xander.lab.common.security.ConnectionRegistry;
import com.xander.lab.common.security.SecurityStateCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 本地安全平面应用逻辑测试。
 *
 * <p>这里用<b>真实</b>的 {@link SecurityStateCache} 与 {@link ConnectionRegistry}
 * 而不是 mock：本类的价值就在于"标记缓存"和"关闭连接"这两件事的<b>组合顺序与次数</b>，
 * mock 掉它们之后测试只能断言"调用了某个方法"，无法发现"重复投递导致重复断连"
 * 或"解封顺手把连接也关了"这类真实缺陷。</p>
 */
class SecurityStateApplierTest {

    private static final long ACCOUNT = 42L;

    private SecurityStateCache cache;
    private ConnectionRegistry registry;
    private SecurityStateApplier applier;

    @BeforeEach
    void setUp() {
        // 缓存用 spy 包一层：版本闸门在"同版本重放"这一档上<b>没有</b>可观测的
        // 状态差异（写入的是同一个版本号），只能靠"有没有调用写入方法"来钉住
        // 版本闸门的 `incoming > cached` 判据。
        cache = spy(new SecurityStateCache());
        registry = new ConnectionRegistry();
        applier = new SecurityStateApplier(cache, registry);
    }

    @Test
    void disableMarksCacheAndClosesEstablishedConnections() {
        RecordingHandle sse = new RecordingHandle(ACCOUNT, ConnectionRegistry.KIND_SSE);
        RecordingHandle ws = new RecordingHandle(ACCOUNT, ConnectionRegistry.KIND_WS);
        registry.register(sse);
        registry.register(ws);

        SecurityStateApplier.Outcome outcome = applier.apply(ACCOUNT, true, 7L);

        assertThat(cache.isDisabled(ACCOUNT)).isTrue();
        assertThat(cache.versionOf(ACCOUNT)).hasValue(7L);
        assertThat(outcome.newlyDisabled()).isTrue();
        assertThat(outcome.closedConnections()).isEqualTo(2);
        assertThat(sse.closeReason).isEqualTo(ConnectionRegistry.REASON_ACCOUNT_DISABLED);
        assertThat(ws.closeReason).isEqualTo(ConnectionRegistry.REASON_ACCOUNT_DISABLED);
    }

    @Test
    void disableOnlyClosesConnectionsOfTheTargetAccount() {
        RecordingHandle mine = new RecordingHandle(ACCOUNT, ConnectionRegistry.KIND_SSE);
        RecordingHandle other = new RecordingHandle(ACCOUNT + 1, ConnectionRegistry.KIND_SSE);
        registry.register(mine);
        registry.register(other);

        applier.apply(ACCOUNT, true, 1L);

        assertThat(mine.closeReason).isNotNull();
        assertThat(other.closeReason).as("不能误伤其他账号的连接").isNull();
        assertThat(cache.isDisabled(ACCOUNT + 1)).isFalse();
    }

    @Test
    void repeatedDisableDoesNotCloseAgain() {
        RecordingHandle handle = new RecordingHandle(ACCOUNT, ConnectionRegistry.KIND_SSE);
        registry.register(handle);

        applier.apply(ACCOUNT, true, 1L);
        handle.closeCount = 0;              // 假装连接被重新建立
        registry.register(handle);
        SecurityStateApplier.Outcome second = applier.apply(ACCOUNT, true, 2L);

        assertThat(second.newlyDisabled()).as("已是封禁态，不算一次新的翻转").isFalse();
        assertThat(second.closedConnections()).isZero();
        assertThat(handle.closeCount).as("重复投递不得重复断连").isZero();
        // 版本号仍要更新到最新值，否则按版本下钻的指标会读到过期版本
        assertThat(cache.versionOf(ACCOUNT)).hasValue(2L);
    }

    @Test
    void enableRemovesFromCacheWithoutClosingConnections() {
        RecordingHandle handle = new RecordingHandle(ACCOUNT, ConnectionRegistry.KIND_SSE);
        registry.register(handle);
        applier.apply(ACCOUNT, true, 1L);
        handle.closeCount = 0;
        registry.register(handle);

        SecurityStateApplier.Outcome outcome = applier.apply(ACCOUNT, false, 2L);

        assertThat(cache.isDisabled(ACCOUNT)).isFalse();
        assertThat(outcome.newlyEnabled()).isTrue();
        assertThat(outcome.closedConnections()).isZero();
        assertThat(handle.closeCount).as("解封不得关闭任何连接").isZero();
    }

    @Test
    void enableWhenNotDisabledIsNoop() {
        SecurityStateApplier.Outcome outcome = applier.apply(ACCOUNT, false, 1L);

        assertThat(outcome.newlyEnabled()).isFalse();
        assertThat(outcome.newlyDisabled()).isFalse();
        assertThat(cache.isDisabled(ACCOUNT)).isFalse();
    }

    @Test
    void disableAfterEnableCountsAsAFreshTransition() {
        RecordingHandle handle = new RecordingHandle(ACCOUNT, ConnectionRegistry.KIND_SSE);
        registry.register(handle);

        assertThat(applier.apply(ACCOUNT, true, 1L).newlyDisabled()).isTrue();
        assertThat(applier.apply(ACCOUNT, false, 2L).newlyEnabled()).isTrue();

        handle.closeCount = 0;
        registry.register(handle);
        SecurityStateApplier.Outcome reBan = applier.apply(ACCOUNT, true, 3L);

        assertThat(reBan.newlyDisabled()).as("解封后再封禁必须是一次新的翻转").isTrue();
        assertThat(reBan.closedConnections()).isEqualTo(1);
    }

    @Test
    void aStaleReplayDoesNotRegressTheCachedVersionNorCloseConnectionsAgain() {
        RecordingHandle handle = new RecordingHandle(ACCOUNT, ConnectionRegistry.KIND_SSE);
        registry.register(handle);
        applier.apply(ACCOUNT, true, 7L);
        handle.closeCount = 0;
        registry.register(handle);

        // 一条迟到的重放，版本比缓存里旧（incoming <= cached 不应用）
        SecurityStateApplier.Outcome stale = applier.apply(ACCOUNT, true, 3L);

        assertThat(stale.newlyDisabled()).isFalse();
        assertThat(stale.closedConnections()).isZero();
        assertThat(handle.closeCount).isZero();
        assertThat(cache.versionOf(ACCOUNT))
                .as("版本号被指标用来按版本下钻，回退会让指标读到过期版本")
                .hasValue(7L);
    }

    @Test
    void aSameVersionReplayIsAlsoIgnored() {
        applier.apply(ACCOUNT, true, 7L);

        SecurityStateApplier.Outcome same = applier.apply(ACCOUNT, true, 7L);

        assertThat(same.newlyDisabled()).isFalse();
        assertThat(cache.versionOf(ACCOUNT)).hasValue(7L);
        // 判据是 `incoming > cached`，所以同版本也必须走"不应用"这一支。
        // 同版本写入与不写入的状态完全一样，只能靠调用次数区分：
        // 有闸门 ⇒ 只有第一次 apply 写了一次；没闸门 ⇒ 两次都写（变成 2）。
        verify(cache, times(1)).markDisabled(ACCOUNT, 7L);
    }

    @Test
    void enableHasNoVersionGateBecauseTheCacheHoldsNoVersionForEnabledAccounts() {
        applier.apply(ACCOUNT, true, 9L);

        // 解封方向刻意没有闸门：封禁状态一解除，缓存里就不再有这个账号的版本号，
        // 因此"incoming > cached"根本无从比较。见 SecurityStateApplier 的说明。
        SecurityStateApplier.Outcome enabled = applier.apply(ACCOUNT, false, 4L);

        assertThat(enabled.newlyEnabled()).isTrue();
        assertThat(cache.isDisabled(ACCOUNT)).isFalse();
        assertThat(cache.versionOf(ACCOUNT)).isEmpty();
    }

    @Test
    void rejectsNullAccountIdInsteadOfSilentlySkipping() {
        assertThatThrownBy(() -> applier.apply(null, true, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountId");
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
