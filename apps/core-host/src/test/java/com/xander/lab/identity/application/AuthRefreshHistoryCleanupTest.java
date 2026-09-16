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
 * 历史清理任务测试（V9 §42）。
 *
 * <p>清理任务的循环骨架由 {@link RetentionBatchLoop} 单独测；这里只测"这个任务
 * 接对了没有"以及两个注解约束。</p>
 */
@ExtendWith(MockitoExtension.class)
class AuthRefreshHistoryCleanupTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-15T04:00:00Z");
    private static final Clock FIXED = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

    @Mock
    private AuthRefreshRetentionService retentionService;

    private RefreshTokenProperties properties;
    private AuthRefreshHistoryCleanup cleanup;

    @BeforeEach
    void setUp() {
        properties = new RefreshTokenProperties();
        properties.setHistoryCleanupSleepMs(0);      // 测试里不真的睡
        properties.setCleanupSlowBatchMs(60_000);    // 不触发退让
        cleanup = new AuthRefreshHistoryCleanup(
                retentionService, new RetentionBatchLoop(properties), properties, FIXED);
    }

    @Test
    void usesTheConfiguredTimeBoundaryBatchSizeAndBatchCeiling() {
        properties.setHistoryCleanupBatchSize(123);
        properties.setHistoryCleanupMaxBatches(2);
        when(retentionService.deleteExpiredHistory(any(), anyInt())).thenReturn(123);

        cleanup.cleanupExpiredHistory();

        // 两批都是"吃满"，所以走到批数上界；时间边界必须来自注入的时钟
        verify(retentionService, times(2)).deleteExpiredHistory(NOW, 123);
    }

    @Test
    void stopsAfterASingleBatchWhenTheTableIsDrained() {
        properties.setHistoryCleanupBatchSize(500);
        when(retentionService.deleteExpiredHistory(any(), anyInt())).thenReturn(5);

        cleanup.cleanupExpiredHistory();

        verify(retentionService, times(1)).deleteExpiredHistory(NOW, 500);
    }

    /**
     * 非正数批大小必须被夹到 1。
     *
     * <p>{@code LIMIT 0} 永远返回 0 行，于是"本批没吃满即排空"的判定永远不成立，
     * 循环会白跑满 maxBatches 次。夹到 1 之后至少语义自洽。</p>
     */
    @Test
    void clampsNonPositiveBatchSizeToOne() {
        properties.setHistoryCleanupBatchSize(0);
        properties.setHistoryCleanupMaxBatches(1);
        when(retentionService.deleteExpiredHistory(any(), anyInt())).thenReturn(0);

        cleanup.cleanupExpiredHistory();

        verify(retentionService).deleteExpiredHistory(NOW, 1);
    }

    @Test
    void databaseFailureDoesNotEscapeTheScheduler() {
        when(retentionService.deleteExpiredHistory(any(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> cleanup.cleanupExpiredHistory()).doesNotThrowAnyException();
    }

    /**
     * 注解守卫（V9 §68）：{@code global_once} 的落地形式就是"带 {@code @SchedulerLock}"。
     * 删掉它除了多实例下重复删除之外没有任何症状，所以必须显式守。
     */
    @Test
    void cleanupExpiredHistoryIsGloballyLocked() throws NoSuchMethodException {
        Method method = AuthRefreshHistoryCleanup.class.getMethod("cleanupExpiredHistory");

        assertThat(method.getAnnotation(Scheduled.class))
                .as("@Scheduled 缺失则任务不会运行")
                .isNotNull();
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);
        assertThat(lock).as("global_once 任务必须带 @SchedulerLock（V9 §68）").isNotNull();
        assertThat(lock.name())
                .as("锁名必须等于 scheduled-ownership.yaml 里的 id")
                .isEqualTo("auth-refresh-history-cleanup");
    }

    /**
     * ⚠️ 调度方法本身<b>不得</b>是事务性的。
     *
     * <p>§42 要求「每批独立事务」。把 {@code @Transactional} 写在循环外面是这里最容易犯、
     * 后果最重的错：20 批 × 500 行会在一个事务里删 10,000 行，并且批间 sleep 期间
     * <b>一直持锁</b>（4~8 秒），于是并发的 refresh 写入撞上锁等待、undo log 与复制延迟
     * 一起膨胀。它没有任何功能性症状 —— 测试全绿、功能正常，只在生产高峰暴露。</p>
     *
     * <p>事务边界由 {@link AuthRefreshRetentionService} 提供，本类刻意不参与事务。</p>
     */
    @Test
    void schedulerMethodItselfIsNotTransactional() throws NoSuchMethodException {
        Method method = AuthRefreshHistoryCleanup.class.getMethod("cleanupExpiredHistory");

        assertThat(method.getAnnotation(Transactional.class))
                .as("在循环外面加 @Transactional 会把 N 批合成一个长事务，且批间 sleep 期间持锁")
                .isNull();
    }

    /**
     * {@code @Scheduled} 只认占位符、不认 bean，所以 interval 的默认值在代码里写了两遍
     * （注解占位符 + {@link RefreshTokenProperties}）。
     *
     * <p>改一处漏一处会静默生效：占位符默认值只在"属性未被显式配置"时起作用，
     * 于是漂移后的表现是"配置类里写着 5 分钟，实际每 30 秒跑一次" —— 或者反过来，
     * 清理间隔变成 5 分钟而没人知道为什么变慢了。</p>
     */
    @Test
    void scheduledPlaceholderDefaultMatchesThePropertyDefault() throws NoSuchMethodException {
        String raw = AuthRefreshHistoryCleanup.class.getMethod("cleanupExpiredHistory")
                .getAnnotation(Scheduled.class).fixedDelayString();

        int colon = raw.indexOf(':');
        int close = raw.lastIndexOf('}');
        assertThat(colon).as("占位符必须带默认值: %s", raw).isGreaterThan(0);
        assertThat(close).isGreaterThan(colon);

        assertThat(raw.substring(colon + 1, close))
                .as("注解里的默认值必须与 RefreshTokenProperties 的默认值一致（%s）", raw)
                .isEqualTo(String.valueOf(properties.getHistoryCleanupIntervalMs()));
    }
}
