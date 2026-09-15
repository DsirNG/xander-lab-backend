package com.xander.lab.identity.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 保留策略批次循环测试（V9 §42 / §43）。
 *
 * <p>这里守的都是"跑得通但语义错了"的边界：批数上界、时间边界是否整轮一致、
 * 中断是否被吞掉、单批变慢是否退让。它们在生产里表现为"清理任务看起来正常，
 * 但主库在某个时间点开始抖动"，所以必须靠测试而不是靠观察。</p>
 */
class RetentionBatchLoopTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 15, 12, 0, 0);

    private RefreshTokenProperties properties;
    private RetentionBatchLoop loop;

    @BeforeEach
    void setUp() {
        properties = new RefreshTokenProperties();
        properties.setHistoryCleanupSleepMs(0);   // 默认不真的睡，除非测试显式设置
        properties.setCleanupSlowBatchMs(2_000);
        loop = new RetentionBatchLoop(properties);
    }

    @Test
    void drainsUntilABatchIsNotFull() {
        AtomicInteger calls = new AtomicInteger();
        // 前两批吃满，第三批只剩 3 行 → 说明排空，停止
        int total = loop.run("测试", NOW, 10, 100,
                (now, size) -> calls.getAndIncrement() < 2 ? size : 3);

        assertThat(calls.get()).isEqualTo(3);
        assertThat(total).isEqualTo(23);
    }

    @Test
    void stopsAtTheBatchCeilingInsteadOfRunningForever() {
        AtomicInteger calls = new AtomicInteger();
        // 永远吃满：只能靠批数上界停下来
        int total = loop.run("测试", NOW, 10, 4, (now, size) -> {
            calls.incrementAndGet();
            return size;
        });

        assertThat(calls.get()).as("必须停在 maxBatches，否则一轮会跑很久").isEqualTo(4);
        assertThat(total).isEqualTo(40);
    }

    /**
     * 整轮必须复用同一个时间边界。
     *
     * <p>每批各取一次 {@code now()} 会让删除边界随时间前移：一轮跑得越久，
     * 越可能删到"刚刚才到期"的行 —— 效果就是"清理跑得越勤，实际保留窗口越短"，
     * 而 §40 的 24 小时保留是安全语义（Reuse 判定窗口）。</p>
     */
    @Test
    void passesTheSameTimeBoundaryToEveryBatch() {
        List<LocalDateTime> seen = new ArrayList<>();
        loop.run("测试", NOW, 10, 3, (now, size) -> {
            seen.add(now);
            return size;
        });

        assertThat(seen).hasSize(3).containsOnly(NOW);
    }

    /** 单批变慢即退让：这是 §42「数据库负载高时自动暂停」的落地形式。 */
    @Test
    void backsOffWhenABatchIsSlowerThanTheThreshold() {
        properties.setCleanupSlowBatchMs(0);
        AtomicInteger calls = new AtomicInteger();

        int total = loop.run("测试", NOW, 10, 100, (now, size) -> {
            calls.incrementAndGet();
            sleepQuietly(5);      // 超过阈值 0ms
            return size;
        });

        assertThat(calls.get()).as("第一批就变慢，不应该继续打第二批").isEqualTo(1);
        assertThat(total).isEqualTo(10);
    }

    @Test
    void keepsGoingWhileBatchesAreFast() {
        AtomicInteger calls = new AtomicInteger();
        loop.run("测试", NOW, 10, 3, (now, size) -> {
            calls.incrementAndGet();
            return size;
        });

        assertThat(calls.get()).isEqualTo(3);
    }

    /**
     * 中断必须被恢复并终止本轮。
     *
     * <p>吞掉 {@link InterruptedException} 会让关闭流程无法打断清理循环：
     * 线程池以为任务已响应中断，于是不再等待它结束。</p>
     */
    @Test
    void restoresInterruptFlagAndStopsTheRun() {
        properties.setHistoryCleanupSleepMs(5);
        AtomicInteger calls = new AtomicInteger();
        int total;
        try {
            total = loop.run("测试", NOW, 10, 5, (now, size) -> {
                if (calls.incrementAndGet() == 2) {
                    Thread.currentThread().interrupt();   // 第 2 批之后进入 sleep 时被打断
                }
                return size;
            });

            assertThat(Thread.currentThread().isInterrupted())
                    .as("中断位必须被恢复，不能吞掉").isTrue();
        } finally {
            Thread.interrupted();   // 清理，避免污染同线程上的后续测试
        }

        assertThat(calls.get()).isEqualTo(2);
        assertThat(total).isEqualTo(20);
    }

    @Test
    void containsBatchFailureAndReturnsWhatWasDoneSoFar() {
        AtomicInteger calls = new AtomicInteger();
        int[] result = new int[1];

        assertThatCode(() -> result[0] = loop.run("测试", NOW, 10, 100, (now, size) -> {
            if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException("db down");
            }
            return size;
        })).doesNotThrowAnyException();

        assertThat(result[0]).as("失败前完成的批次要计入").isEqualTo(10);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void containsFailureOnTheVeryFirstBatch() {
        int result = loop.run("测试", NOW, 10, 100, (now, size) -> {
            throw new IllegalStateException("db down");
        });

        assertThat(result).isZero();
    }

    /** 不睡在"排空"之后：那会白白推迟下一次调度（fixedDelay 从方法返回才开始计时）。 */
    @Test
    void doesNotSleepAfterDraining() {
        properties.setHistoryCleanupSleepMs(60);

        long startedAt = System.nanoTime();
        loop.run("测试", NOW, 10, 5, (now, size) -> 3);   // 第一批就没吃满 → 直接排空
        long elapsedMs = elapsedMillis(startedAt);

        assertThat(elapsedMs).as("排空后不应再有批间停顿，实际 %dms", elapsedMs).isLessThan(50);
    }

    /** 同理不睡在"最后一批"之后：批数上界是"干完这批就走"，不是"干完再等一会"。 */
    @Test
    void doesNotSleepAfterTheLastAllowedBatch() {
        properties.setHistoryCleanupSleepMs(60);

        long startedAt = System.nanoTime();
        loop.run("测试", NOW, 10, 1, (now, size) -> size);   // 唯一一批，且吃满
        long elapsedMs = elapsedMillis(startedAt);

        assertThat(elapsedMs).as("上界批之后不应再停顿，实际 %dms", elapsedMs).isLessThan(50);
    }

    @Test
    void sleepsBetweenBatches() {
        properties.setHistoryCleanupSleepMs(30);

        long startedAt = System.nanoTime();
        loop.run("测试", NOW, 10, 3, (now, size) -> size);   // 3 批 → 2 次批间停顿
        long elapsedMs = elapsedMillis(startedAt);

        assertThat(elapsedMs).as("3 批之间应至少停顿 2 次，实际 %dms", elapsedMs).isGreaterThanOrEqualTo(60);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}
