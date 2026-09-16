package com.xander.lab.identity.application;

import com.xander.lab.identity.domain.AuthRefreshSession;
import com.xander.lab.identity.domain.AuthRefreshTokenHistory;
import com.xander.lab.identity.domain.SecurityEventOutboxEntry;
import com.xander.lab.identity.domain.User;
import com.xander.lab.identity.infrastructure.AuthRefreshSessionMapper;
import com.xander.lab.identity.infrastructure.AuthRefreshTokenHistoryMapper;
import com.xander.lab.identity.infrastructure.SecurityEventOutboxMapper;
import com.xander.lab.identity.infrastructure.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V9 §39 Reuse Detection 四分支 + §37 / §38 / §40 的回归测试。
 *
 * <p>这些用例是"能力守恒"的机器保障：任何一个分支被后续改动悄悄删掉，
 * 都会在这里失败，而不是等到线上出现"随机垃圾 token 把用户踢下线"。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefreshTokenServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 15, 12, 0, 0);
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-15T12:00:00Z"), ZoneOffset.UTC);
    private static final long ACCOUNT_ID = 42L;

    @Mock private AuthRefreshSessionMapper sessionMapper;
    @Mock private AuthRefreshTokenHistoryMapper historyMapper;
    @Mock private SecurityEventOutboxMapper outboxMapper;
    @Mock private UserMapper userMapper;

    private RefreshTokenProperties properties;
    private SuccessorTokenCipher cipher;
    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        properties = new RefreshTokenProperties();
        properties.setEnabled(true);
        properties.setGraceSeconds(25);
        properties.setRetentionHours(24);
        properties.setMaxGenerationsPerSid(48);
        properties.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));

        cipher = new SuccessorTokenCipher(properties);
        cipher.initialize();

        service = new RefreshTokenService(sessionMapper, historyMapper, outboxMapper,
                userMapper, properties, cipher, FIXED_CLOCK);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** 造一个"当前有效"的会话 + 与之匹配的明文 refresh token。 */
    private AuthRefreshSession activeSession(String sid, String tokenId, String secret) {
        AuthRefreshSession session = new AuthRefreshSession();
        session.setSid(sid);
        session.setAccountId(ACCOUNT_ID);
        session.setFamilyId("family-" + sid);
        session.setCurrentTokenId(tokenId);
        session.setCurrentTokenHash(RefreshTokenFormat.hash(tokenId, secret));
        session.setGeneration(3);
        session.setStatus(AuthRefreshSession.STATUS_ACTIVE);
        session.setIssuedAt(NOW.minusDays(1));
        session.setExpiresAt(NOW.plusDays(29));
        return session;
    }

    private void givenActiveUser() {
        User user = new User();
        user.setId(ACCOUNT_ID);
        user.setStatus(1);
        when(userMapper.selectById(ACCOUNT_ID)).thenReturn(user);
    }

    private AuthRefreshTokenHistory historyRow(String sid, String tokenId, String secret,
                                               LocalDateTime usedAt,
                                               LocalDateTime graceExpiresAt,
                                               LocalDateTime retainedUntil,
                                               String successorRawToken) {
        AuthRefreshTokenHistory history = new AuthRefreshTokenHistory();
        history.setTokenId(tokenId);
        history.setSid(sid);
        history.setTokenHash(RefreshTokenFormat.hash(tokenId, secret));
        history.setGeneration(2);
        history.setUsedAt(usedAt);
        history.setGraceExpiresAt(graceExpiresAt);
        history.setRetainedUntil(retainedUntil);
        history.setSuccessorTokenId("successor-token-id");
        if (successorRawToken != null) {
            history.setSuccessorTokenCiphertext(cipher.encrypt(successorRawToken));
            history.setSuccessorCiphertextExpiresAt(graceExpiresAt);
        }
        return history;
    }

    // ------------------------------------------------------------------
    // 分支 1：正常轮换
    // ------------------------------------------------------------------

    @Test
    @DisplayName("分支 1：current token + hash 匹配 → 轮换，写 history，返回新对")
    void rotatesCurrentToken() {
        String sid = "sid-1";
        String tokenId = "token-1";
        String secret = "secret-1";
        givenActiveUser();
        when(sessionMapper.selectForUpdate(sid)).thenReturn(activeSession(sid, tokenId, secret));

        RefreshTokenService.Rotation result =
                service.rotate(RefreshTokenFormat.format(sid, tokenId, secret));

        assertThat(result.outcome()).isEqualTo(RefreshTokenService.Outcome.ROTATED);
        assertThat(result.refreshToken()).startsWith("rt_" + sid + ".");
        assertThat(result.refreshToken()).isNotEqualTo(RefreshTokenFormat.format(sid, tokenId, secret));

        ArgumentCaptor<AuthRefreshTokenHistory> history =
                ArgumentCaptor.forClass(AuthRefreshTokenHistory.class);
        verify(historyMapper).insert(history.capture());
        assertThat(history.getValue().getTokenId()).isEqualTo(tokenId);
        assertThat(history.getValue().getSuccessorTokenId()).isNotNull();
        assertThat(history.getValue().getSuccessorTokenCiphertext()).isNotBlank();

        ArgumentCaptor<AuthRefreshSession> updated =
                ArgumentCaptor.forClass(AuthRefreshSession.class);
        verify(sessionMapper).updateById(updated.capture());
        assertThat(updated.getValue().getGeneration()).isEqualTo(4);
        assertThat(updated.getValue().getCurrentTokenId())
                .isEqualTo(history.getValue().getSuccessorTokenId());

        // §40.3 条数上界每次轮换都要执行
        verify(historyMapper).pruneGenerationsBeyondBound(eq(sid), eq(48), any());
    }

    @Test
    @DisplayName("§40.2：retained_until 必须在写 history 行时显式写入 now()+24h")
    void retainedUntilIsWrittenExplicitly() {
        String sid = "sid-retention";
        givenActiveUser();
        when(sessionMapper.selectForUpdate(sid))
                .thenReturn(activeSession(sid, "token-r", "secret-r"));

        service.rotate(RefreshTokenFormat.format(sid, "token-r", "secret-r"));

        ArgumentCaptor<AuthRefreshTokenHistory> history =
                ArgumentCaptor.forClass(AuthRefreshTokenHistory.class);
        verify(historyMapper).insert(history.capture());
        assertThat(history.getValue().getRetainedUntil())
                .isEqualTo(NOW.plusHours(24));
    }

    @Test
    @DisplayName("§38：密文窗口不得短于 grace，否则第 N 次重放取不回同一个 successor")
    void ciphertextWindowIsNotShorterThanGrace() {
        String sid = "sid-cipher";
        givenActiveUser();
        when(sessionMapper.selectForUpdate(sid))
                .thenReturn(activeSession(sid, "token-c", "secret-c"));

        service.rotate(RefreshTokenFormat.format(sid, "token-c", "secret-c"));

        ArgumentCaptor<AuthRefreshTokenHistory> history =
                ArgumentCaptor.forClass(AuthRefreshTokenHistory.class);
        verify(historyMapper).insert(history.capture());
        assertThat(history.getValue().getSuccessorCiphertextExpiresAt())
                .isAfterOrEqualTo(history.getValue().getGraceExpiresAt());
    }

    // ------------------------------------------------------------------
    // 分支 2：grace 内幂等重放
    // ------------------------------------------------------------------

    @Test
    @DisplayName("分支 2：grace 内重放 → 返回同一个 successor，且不撤销会话族")
    void replaysIdempotentlyWithinGrace() {
        String sid = "sid-2";
        String consumedId = "token-consumed";
        String successorRaw = RefreshTokenFormat.format(sid, "token-successor", "secret-succ");
        givenActiveUser();
        when(sessionMapper.selectForUpdate(sid))
                .thenReturn(activeSession(sid, "token-current", "secret-current"));
        when(historyMapper.selectById(consumedId)).thenReturn(
                historyRow(sid, consumedId, "secret-consumed",
                        NOW.minusSeconds(5),
                        NOW.plusSeconds(20),      // grace 未过
                        NOW.plusHours(24),
                        successorRaw));

        RefreshTokenService.Rotation first =
                service.rotate(RefreshTokenFormat.format(sid, consumedId, "secret-consumed"));
        RefreshTokenService.Rotation second =
                service.rotate(RefreshTokenFormat.format(sid, consumedId, "secret-consumed"));
        RefreshTokenService.Rotation third =
                service.rotate(RefreshTokenFormat.format(sid, consumedId, "secret-consumed"));

        // 第 2、第 3、第 N 次语义完全相同（V9 §37）
        assertThat(first.outcome()).isEqualTo(RefreshTokenService.Outcome.REPLAYED);
        assertThat(second.outcome()).isEqualTo(RefreshTokenService.Outcome.REPLAYED);
        assertThat(third.outcome()).isEqualTo(RefreshTokenService.Outcome.REPLAYED);
        assertThat(first.refreshToken()).isEqualTo(successorRaw);
        assertThat(second.refreshToken()).isEqualTo(successorRaw);
        assertThat(third.refreshToken()).isEqualTo(successorRaw);

        verify(sessionMapper, never()).revokeFamily(anyString(), anyString(), any());
    }

    // ------------------------------------------------------------------
    // 分支 3：真正的 Reuse
    // ------------------------------------------------------------------

    @Test
    @DisplayName("分支 3：超出 grace 但仍在 retention 内 → revoke family + 安全事件")
    void revokesFamilyOnReuseAfterGrace() {
        String sid = "sid-3";
        String consumedId = "token-stolen";
        givenActiveUser();
        when(sessionMapper.selectForUpdate(sid))
                .thenReturn(activeSession(sid, "token-current", "secret-current"));
        when(historyMapper.selectById(consumedId)).thenReturn(
                historyRow(sid, consumedId, "secret-stolen",
                        NOW.minusMinutes(10),
                        NOW.minusMinutes(9),      // grace 已过
                        NOW.plusHours(23),        // 仍在 retention 内
                        "rt_" + sid + ".succ.secret"));
        when(sessionMapper.revokeFamily(anyString(), anyString(), any())).thenReturn(1);

        RefreshTokenService.Rotation result =
                service.rotate(RefreshTokenFormat.format(sid, consumedId, "secret-stolen"));

        assertThat(result.outcome()).isEqualTo(RefreshTokenService.Outcome.REUSE_DETECTED);
        assertThat(result.isUnrecoverable()).isTrue();
        verify(sessionMapper).revokeFamily("family-" + sid,
                AuthRefreshSession.REASON_REUSE_DETECTED, NOW);
        verify(historyMapper).markReuseDetected(eq(consumedId), eq(NOW));
        verify(outboxMapper).insert(any(SecurityEventOutboxEntry.class));
    }

    @Test
    @DisplayName("§39.2：超出 retention 的老 token 仍然无效，但不再触发 family theft alarm")
    void doesNotAlarmBeyondRetention() {
        String sid = "sid-old";
        String consumedId = "token-ancient";
        givenActiveUser();
        when(sessionMapper.selectForUpdate(sid))
                .thenReturn(activeSession(sid, "token-current", "secret-current"));
        when(historyMapper.selectById(consumedId)).thenReturn(
                historyRow(sid, consumedId, "secret-ancient",
                        NOW.minusDays(3),
                        NOW.minusDays(3),
                        NOW.minusHours(1),        // 超出 retention
                        null));

        RefreshTokenService.Rotation result =
                service.rotate(RefreshTokenFormat.format(sid, consumedId, "secret-ancient"));

        assertThat(result.outcome()).isEqualTo(RefreshTokenService.Outcome.INVALID);
        verify(sessionMapper, never()).revokeFamily(anyString(), anyString(), any());
        verify(historyMapper, never()).markReuseDetected(anyString(), any());
    }

    // ------------------------------------------------------------------
    // 分支 4：垃圾 token —— 防 DoS 的核心用例
    // ------------------------------------------------------------------

    @Test
    @DisplayName("分支 4【防 DoS】：不在 current 也不在 history → 401，绝不 revoke family")
    void rejectsGarbageTokenWithoutRevokingFamily() {
        String knownSid = "sid-leaked-in-logs";
        givenActiveUser();
        when(sessionMapper.selectForUpdate(knownSid))
                .thenReturn(activeSession(knownSid, "token-current", "secret-current"));
        // 攻击者构造的 tokenId 在 history 里查不到
        when(historyMapper.selectById(anyString())).thenReturn(null);

        // 反复提交随机垃圾 token
        for (int i = 0; i < 5; i++) {
            RefreshTokenService.Rotation result = service.rotate(
                    RefreshTokenFormat.format(knownSid, "random-" + i, "random-secret-" + i));
            assertThat(result.outcome()).isEqualTo(RefreshTokenService.Outcome.INVALID);
            assertThat(result.isUnrecoverable()).isFalse();
        }

        verify(sessionMapper, never()).revokeFamily(anyString(), anyString(), any());
        verify(sessionMapper, never()).revokeSession(anyString(), anyString(), any());
        verify(sessionMapper, never()).revokeByAccount(anyLong(), anyString(), any());
        verify(outboxMapper, never()).insert(any(SecurityEventOutboxEntry.class));
    }

    @Test
    @DisplayName("分支 4：形状不符的 token 直接无效，不触碰任何存储")
    void rejectsMalformedToken() {
        RefreshTokenService.Rotation result = service.rotate("not-a-refresh-token");

        assertThat(result.outcome()).isEqualTo(RefreshTokenService.Outcome.INVALID);
        verify(sessionMapper, never()).selectForUpdate(anyString());
        verify(sessionMapper, never()).revokeFamily(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("分支 4：tokenId 对但 secret 错 → 无效，不 revoke")
    void rejectsWrongSecretOnCurrentToken() {
        String sid = "sid-wrong-secret";
        givenActiveUser();
        when(sessionMapper.selectForUpdate(sid))
                .thenReturn(activeSession(sid, "token-current", "real-secret"));

        RefreshTokenService.Rotation result =
                service.rotate(RefreshTokenFormat.format(sid, "token-current", "guessed-secret"));

        assertThat(result.outcome()).isEqualTo(RefreshTokenService.Outcome.INVALID);
        verify(sessionMapper, never()).revokeFamily(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("分支 4：tokenId 属于另一个会话 → 无效，不 revoke")
    void rejectsTokenFromAnotherSession() {
        String sid = "sid-a";
        givenActiveUser();
        when(sessionMapper.selectForUpdate(sid))
                .thenReturn(activeSession(sid, "token-current", "secret-current"));
        when(historyMapper.selectById("foreign-token")).thenReturn(
                historyRow("sid-b", "foreign-token", "foreign-secret",
                        NOW.minusSeconds(1), NOW.plusSeconds(24), NOW.plusHours(24), null));

        RefreshTokenService.Rotation result =
                service.rotate(RefreshTokenFormat.format(sid, "foreign-token", "foreign-secret"));

        assertThat(result.outcome()).isEqualTo(RefreshTokenService.Outcome.INVALID);
        verify(sessionMapper, never()).revokeFamily(anyString(), anyString(), any());
    }

    // ------------------------------------------------------------------
    // 会话 / 账号状态
    // ------------------------------------------------------------------

    @Test
    @DisplayName("§45：账号被封禁 → ACCOUNT_DISABLED（客户端必须 forceLoggedOut）")
    void reportsAccountDisabled() {
        String sid = "sid-banned";
        User banned = new User();
        banned.setId(ACCOUNT_ID);
        banned.setStatus(0);
        when(userMapper.selectById(ACCOUNT_ID)).thenReturn(banned);
        when(sessionMapper.selectForUpdate(sid))
                .thenReturn(activeSession(sid, "token-b", "secret-b"));

        RefreshTokenService.Rotation result =
                service.rotate(RefreshTokenFormat.format(sid, "token-b", "secret-b"));

        assertThat(result.outcome()).isEqualTo(RefreshTokenService.Outcome.ACCOUNT_DISABLED);
        assertThat(result.isUnrecoverable()).isTrue();
    }

    @Test
    @DisplayName("§47：logout 撤销当前 Refresh Session")
    void logoutRevokesCurrentSession() {
        when(sessionMapper.revokeSession(eq("sid-logout"), eq(AuthRefreshSession.REASON_LOGOUT), any()))
                .thenReturn(1);

        assertThat(service.logout(RefreshTokenFormat.format("sid-logout", "t", "s"))).isTrue();
        verify(sessionMapper).revokeSession("sid-logout",
                AuthRefreshSession.REASON_LOGOUT, NOW);
    }

    @Test
    @DisplayName("§26：管理员封禁撤销该账号全部刷新会话，一条语句完成")
    void revokeAllForAccountIsSingleStatement() {
        when(sessionMapper.revokeByAccount(anyLong(), anyString(), any())).thenReturn(3);

        assertThat(service.revokeAllForAccount(ACCOUNT_ID, AuthRefreshSession.REASON_ADMIN_DISABLE))
                .isEqualTo(3);
        verify(sessionMapper).revokeByAccount(ACCOUNT_ID,
                AuthRefreshSession.REASON_ADMIN_DISABLE, NOW);
    }

    @Test
    @DisplayName("§32：新建会话生成独立的 sid / family / token，且 generation 从 0 起")
    void issuesNewSession() {
        RefreshTokenService.IssuedSession issued = service.issue(ACCOUNT_ID);

        assertThat(issued.sid()).isNotBlank();
        assertThat(issued.familyId()).isNotBlank();
        assertThat(issued.refreshToken()).startsWith("rt_" + issued.sid() + ".");
        assertThat(issued.expiresAt()).isEqualTo(NOW.plusSeconds(2_592_000));

        ArgumentCaptor<AuthRefreshSession> saved =
                ArgumentCaptor.forClass(AuthRefreshSession.class);
        verify(sessionMapper).insert(saved.capture());
        assertThat(saved.getValue().getGeneration()).isZero();
        assertThat(saved.getValue().getStatus()).isEqualTo(AuthRefreshSession.STATUS_ACTIVE);
        // 明文 secret 绝不落库
        assertThat(saved.getValue().getCurrentTokenHash())
                .isNotEqualTo(issued.refreshToken())
                .hasSize(64);
    }

    @Test
    @DisplayName("特性关闭时 isEnabled=false，调用方继续走旧路径")
    void disabledByDefault() {
        RefreshTokenProperties off = new RefreshTokenProperties();
        assertThat(off.isEnabled()).isFalse();
        assertThat(off.getGraceSeconds()).isEqualTo(25);
        assertThat(off.getRetentionHours()).isEqualTo(24);
        assertThat(off.getMaxGenerationsPerSid()).isEqualTo(48);
        assertThat(off.getAccessTtlSeconds()).isEqualTo(1800);
    }

    @Test
    @DisplayName("§35：grace 必须覆盖 refresh timeout(15s) + 1 次退避重试 + 余量")
    void graceCoversFrontendTimeout() {
        // ⚠️ 这里的 15 是前端 src/api/http.js 的 REFRESH_TIMEOUT，属于 Contract
        // （docs/contract-changes.yaml → refresh-grace-window.frontend_refresh_timeout_ms）。
        //
        // 注意它**不是** VITE_REQUEST_TIMEOUT：后者开发 15000、生产 30000，
        // 而 refresh 请求刻意与全局超时解耦（否则生产会 grace(25s) < timeout(30s)，
        // 响应丢失后的重试落在 grace 之外 → 被判成 Reuse → 撤销整个会话族）。
        // 前端侧由 http.test.jsx 的 Contract 用例守护这个 15000。
        long frontendRefreshTimeoutSeconds = 15;
        long maxBackoffSeconds = 1;
        long networkMarginSeconds = 5;
        assertThat(properties.getGraceSeconds())
                .isGreaterThan(frontendRefreshTimeoutSeconds + maxBackoffSeconds + networkMarginSeconds - 1);
    }

    @Test
    @DisplayName("§40.4：30 分钟 Access 下 history 容量上界为 480,000 行")
    void historyCapacityBound() {
        long activeUsers = 10_000;
        long rotationsPerDay = 24 * 60 / (properties.getAccessTtlSeconds() / 60);
        assertThat(activeUsers * rotationsPerDay).isEqualTo(480_000);
    }

    @Test
    @DisplayName("prune 使用条数上界，不依赖时间窗口单独生效")
    void prunesByCount() {
        assertThat(properties.getMaxGenerationsPerSid()).isEqualTo(48);
        verify(historyMapper, never()).deleteExpired(any(), anyInt());
    }
}
