package com.xander.lab.identity.application;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 密文清空任务测试。
 */
@ExtendWith(MockitoExtension.class)
class AuthRefreshCiphertextCleanupTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-15T04:00:00Z");
    private static final Clock FIXED = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

    @Mock
    private AuthRefreshRetentionService retentionService;

    private RefreshTokenProperties properties;
    private AuthRefreshCiphertextCleanup cleanup;

    @BeforeEach
    void setUp() {
        properties = new RefreshTokenProperties();
        properties.setHistoryCleanupSleepMs(0);
        properties.setCleanupSlowBatchMs(60_000);
        cleanup = new AuthRefreshCiphertextCleanup(
                retentionService, new RetentionBatchLoop(properties), properties, FIXED);
    }

    @Test
    void usesTheConfiguredTimeBoundaryBatchSizeAndBatchCeiling() {
        properties.setCiphertextCleanupBatchSize(250);
        properties.setCiphertextCleanupMaxBatches(3);
        when(retentionService.clearExpiredCiphertext(any(), anyInt())).thenReturn(250);

        cleanup.clearExpiredCiphertext();

        verify(retentionService, times(3)).clearExpiredCiphertext(NOW, 250);
    }

    @Test
    void stopsAfterASingleBatchWhenNothingIsDue() {
        properties.setCiphertextCleanupBatchSize(500);
        when(retentionService.clearExpiredCiphertext(any(), anyInt())).thenReturn(0);

        cleanup.clearExpiredCiphertext();

        verify(retentionService, times(1)).clearExpiredCiphertext(NOW, 500);
    }

    /**
     * 本任务不承担安全语义，所以"清理失败"必须是可容忍的。
     *
     * <p>安全语义依赖时间判断（{@code isCiphertextReadable}），不依赖本任务准点运行。
     * 因此数据库抖动导致本轮失败时，正确行为是记日志并在下一轮重试，
     * <b>而不是</b>抛出去打断调度 —— 后者会让"密文永远清不掉"，也会让日志里
     * 出现一条看起来像安全故障的错误。</p>
     */
    @Test
    void databaseFailureDoesNotEscapeTheScheduler() {
        when(retentionService.clearExpiredCiphertext(any(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> cleanup.clearExpiredCiphertext()).doesNotThrowAnyException();
    }

    @Test
    void clearExpiredCiphertextIsGloballyLocked() throws NoSuchMethodException {
        Method method = AuthRefreshCiphertextCleanup.class.getMethod("clearExpiredCiphertext");

        assertThat(method.getAnnotation(Scheduled.class)).isNotNull();
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);
        assertThat(lock).isNotNull();
        assertThat(lock.name())
                .as("锁名必须等于 scheduled-ownership.yaml 里的 id")
                .isEqualTo("auth-refresh-ciphertext-cleanup");
    }

    /** 同 {@code AuthRefreshHistoryCleanupTest}：循环外面不得加 {@code @Transactional}。 */
    @Test
    void schedulerMethodItselfIsNotTransactional() throws NoSuchMethodException {
        Method method = AuthRefreshCiphertextCleanup.class.getMethod("clearExpiredCiphertext");

        assertThat(method.getAnnotation(Transactional.class))
                .as("在循环外面加 @Transactional 会把 N 批合成一个长事务，且批间 sleep 期间持锁")
                .isNull();
    }

    /**
     * 同 {@code AuthRefreshHistoryCleanupTest}：注解占位符的默认值与配置类默认值必须一致。
     *
     * <p>密文清理的间隔是 30~60 秒，比历史清理敏感得多 —— 漂移成 5 分钟会让密文在库里
     * 多留几分钟（虽然不影响安全语义，但会放大它想避免的存储占用）。</p>
     */
    @Test
    void scheduledPlaceholderDefaultMatchesThePropertyDefault() throws NoSuchMethodException {
        String raw = AuthRefreshCiphertextCleanup.class.getMethod("clearExpiredCiphertext")
                .getAnnotation(Scheduled.class).fixedDelayString();

        int colon = raw.indexOf(':');
        int close = raw.lastIndexOf('}');
        assertThat(colon).as("占位符必须带默认值: %s", raw).isGreaterThan(0);
        assertThat(close).isGreaterThan(colon);

        assertThat(raw.substring(colon + 1, close))
                .as("注解里的默认值必须与 RefreshTokenProperties 的默认值一致（%s）", raw)
                .isEqualTo(String.valueOf(properties.getCiphertextCleanupIntervalMs()));
    }
}
