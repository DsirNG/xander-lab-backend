package com.xander.lab.identity.application;

import com.xander.lab.identity.domain.AccountSecurityState;
import com.xander.lab.identity.domain.AuthRefreshSession;
import com.xander.lab.identity.domain.SecurityEventOutboxEntry;
import com.xander.lab.identity.infrastructure.AccountSecurityStateMapper;
import com.xander.lab.identity.infrastructure.SecurityEventOutboxMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 封禁事务边界测试（V9 §26 / §27 / §48）。
 *
 * <p>纯单测拿不到 Spring 事务代理，因此这里断言的是<b>事务内应有的调用序列</b>，
 * 而"它们真的在同一个事务里"由 {@link #applyStateIsTransactional()} 用注解反射守住 ——
 * 注解被删掉时除了这条测试没有任何其他症状，所以必须显式守。</p>
 */
@ExtendWith(MockitoExtension.class)
class AccountSecurityStateServiceTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-09-15T08:00:00Z"), ZoneOffset.UTC);

    @Mock
    private AccountSecurityStateMapper stateMapper;
    @Mock
    private SecurityEventOutboxMapper outboxMapper;
    @Mock
    private RefreshTokenService refreshTokenService;

    private AccountSecurityStateService service;

    @BeforeEach
    void setUp() {
        service = new AccountSecurityStateService(stateMapper, outboxMapper, refreshTokenService, FIXED);
    }

    private AccountSecurityState state(Long accountId, boolean disabled, long version, long changeSeq) {
        AccountSecurityState state = new AccountSecurityState();
        state.setAccountId(accountId);
        state.setDisabled(disabled ? 1 : 0);
        state.setSecurityVersion(version);
        state.setChangeSeq(changeSeq);
        return state;
    }

    /** 走通"分配游标 → 写 SOT → 回读"这条公共前置。 */
    private void stubSuccessfulWrite(Long accountId, boolean disabled) {
        when(stateMapper.currentChangeSeq()).thenReturn(42L);
        when(stateMapper.selectById(accountId)).thenReturn(state(accountId, disabled, 2L, 42L));
    }

    private SecurityEventOutboxEntry captureEvent() {
        ArgumentCaptor<SecurityEventOutboxEntry> captor =
                ArgumentCaptor.forClass(SecurityEventOutboxEntry.class);
        verify(outboxMapper).insert(captor.capture());
        return captor.getValue();
    }

    // ===================== §26：封禁必须原子完成的四件事 =====================

    @Test
    void banWritesSotRevokesSessionsAndWritesOutboxInOrder() {
        stubSuccessfulWrite(7L, true);
        when(refreshTokenService.revokeAllForAccount(7L, AuthRefreshSession.REASON_ADMIN_DISABLE))
                .thenReturn(3);

        AccountSecurityState result = service.applyState(7L, true, "admin-ban operator=1");

        assertThat(result.getSecurityVersion()).isEqualTo(2L);
        assertThat(result.getChangeSeq()).isEqualTo(42L);

        // 顺序有意义：游标必须在写业务行之前分配（LAST_INSERT_ID 依赖同一连接），
        // 事件必须在回读之后写（payload 要带权威的 securityVersion）。
        InOrder order = inOrder(stateMapper, refreshTokenService, outboxMapper);
        order.verify(stateMapper).allocateChangeSeq();
        order.verify(stateMapper).currentChangeSeq();
        order.verify(stateMapper).upsertState(eq(7L), eq(1), eq(42L), any(), eq("admin-ban operator=1"));
        order.verify(refreshTokenService).revokeAllForAccount(7L, AuthRefreshSession.REASON_ADMIN_DISABLE);
        order.verify(stateMapper).selectById(7L);
        order.verify(outboxMapper).insert(any());
    }

    @Test
    void banWritesAccountDisabledEventCarryingVersionAndChangeSeq() {
        stubSuccessfulWrite(7L, true);
        when(refreshTokenService.revokeAllForAccount(anyLong(), anyString())).thenReturn(1);

        service.applyState(7L, true, "admin-ban operator=1");

        SecurityEventOutboxEntry event = captureEvent();
        assertThat(event.getEventType()).isEqualTo(SecurityEventOutboxEntry.TYPE_ACCOUNT_DISABLED);
        assertThat(event.getAggregateType()).isEqualTo("ACCOUNT");
        assertThat(event.getAggregateId()).isEqualTo("7");
        assertThat(event.getStatus()).isEqualTo(SecurityEventOutboxEntry.STATUS_PENDING);
        assertThat(event.getAttempts()).isZero();
        // 事件必须先落库、立即可投递：next_attempt_at = now，而不是 now + 退避。
        assertThat(event.getNextAttemptAt()).isEqualTo(LocalDateTime.of(2026, 9, 15, 8, 0));
        assertThat(event.getPayload())
                .contains("\"accountId\":7")
                .contains("\"securityVersion\":2")
                .contains("\"changeSeq\":42")
                .contains("\"reason\":\"admin-ban operator=1\"");
    }

    // ===================== 封禁与解封的不对称 =====================

    @Test
    void unbanWritesEnabledEventAndDoesNotRevokeSessions() {
        stubSuccessfulWrite(7L, false);

        service.applyState(7L, false, "admin-unban operator=1");

        verify(refreshTokenService, never()).revokeAllForAccount(anyLong(), anyString());
        assertThat(captureEvent().getEventType())
                .isEqualTo(SecurityEventOutboxEntry.TYPE_ACCOUNT_ENABLED);
    }

    // ===================== 自愈：不能静默失败 =====================

    @Test
    void refusesToWriteWhenChangeSeqCannotBeAllocated() {
        // 取不到游标时若继续写，会产生一行 change_seq 为 NULL 的状态，
        // per_instance reconciler 永远拉不到它 —— 一个不会报错的失败。
        when(stateMapper.currentChangeSeq()).thenReturn(null);

        assertThatThrownBy(() -> service.applyState(7L, true, "x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("change_seq");

        verify(stateMapper, never()).upsertState(any(), anyInt(), anyLong(), any(), any());
        verify(outboxMapper, never()).insert(any());
    }

    @Test
    void refusesToEmitEventWhenStateCannotBeReadBack() {
        when(stateMapper.currentChangeSeq()).thenReturn(42L);
        when(stateMapper.selectById(7L)).thenReturn(null);

        assertThatThrownBy(() -> service.applyState(7L, true, "x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("回读失败");

        verify(outboxMapper, never()).insert(any());
    }

    @Test
    void rejectsNullAccountId() {
        assertThatThrownBy(() -> service.applyState(null, true, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===================== 审计字段的边界 =====================

    @Test
    void truncatesOverlongReasonToColumnWidth() {
        stubSuccessfulWrite(7L, true);
        when(refreshTokenService.revokeAllForAccount(anyLong(), anyString())).thenReturn(0);

        service.applyState(7L, true, "x".repeat(300));

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(stateMapper).upsertState(any(), anyInt(), anyLong(), any(), reason.capture());
        assertThat(reason.getValue()).hasSize(200);
    }

    @Test
    void blankReasonBecomesNull() {
        stubSuccessfulWrite(7L, true);
        when(refreshTokenService.revokeAllForAccount(anyLong(), anyString())).thenReturn(0);

        service.applyState(7L, true, "   ");

        verify(stateMapper).upsertState(any(), anyInt(), anyLong(), any(), eq(null));
    }

    @Test
    void escapesQuotesInReasonSoPayloadStaysValidJson() {
        // reason 最终来自管理员输入。一个未转义的引号会让整条事件变成非法 JSON，
        // 而投递端只能看到"解析失败"，排查成本极高。
        stubSuccessfulWrite(7L, true);
        when(refreshTokenService.revokeAllForAccount(anyLong(), anyString())).thenReturn(0);

        service.applyState(7L, true, "say \"hi\"\\now");

        assertThat(captureEvent().getPayload())
                .contains("\"reason\":\"say \\\"hi\\\"\\\\now\"");
    }

    // ===================== 注解守卫 =====================

    @Test
    void applyStateIsTransactional() throws NoSuchMethodException {
        Method method = AccountSecurityStateService.class
                .getMethod("applyState", Long.class, boolean.class, String.class);
        assertThat(method.getAnnotation(Transactional.class))
                .as("applyState 必须带 @Transactional，否则 §26 的原子性无声失效")
                .isNotNull();
    }
}
