package com.xander.lab.identity.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 保留策略的批次循环（V9 §42 / §43 共用的执行骨架）。
 *
 * <h3>为什么抽出来</h3>
 * <p>两个清理任务（历史删除、密文清空）的循环体只有"调哪个方法"不同，其余全是
 * <b>容易写错、且错了没有症状</b>的部分：批数上界、中断处理、批间 jitter、
 * 数据库变慢时的退让。各写一遍的话，下一次有人只给其中一个加退让逻辑，
 * 另一个就会在数据库吃紧时继续硬打 —— 而两个任务都会"正常工作"。</p>
 *
 * <h3>为什么不在循环外面加 {@code @Transactional}</h3>
 * <p>见 {@link AuthRefreshRetentionService}：那会把 20 批合成一个长事务，
 * 并且批间 sleep 期间一直持锁。事务边界由被调用的 bean 提供，
 * 本类<b>刻意不参与</b>事务。</p>
 *
 * <h3>中断为什么必须恢复标志位</h3>
 * <p>吞掉 {@link InterruptedException} 会让关闭流程无法打断一个正在跑的清理循环：
 * 线程池认为任务已经响应了中断，于是不再等待它结束。恢复中断位并主动退出循环，
 * 是本类唯一正确的做法。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetentionBatchLoop {

    /** 一批保留策略操作，返回本批实际影响的行数。 */
    @FunctionalInterface
    public interface BatchOperation {
        int run(LocalDateTime now, int batchSize);
    }

    private final RefreshTokenProperties properties;

    /**
     * 循环执行批次，直到排空 / 达到批数上界 / 数据库变慢 / 被中断。
     *
     * <p>{@code now} 由调用方在<b>整轮开始时</b>取一次并全程复用：每批各自取
     * {@code now()} 会让同一轮的删除边界随时间前移，一个长轮次就可能删掉一批
     * "刚刚才到期"的行 —— 那正是保留策略想要避免的"清理跑得越快、保留窗口越短"。</p>
     *
     * @param label      日志前缀（例如"历史清理"），用于区分两个任务
     * @param now        本轮统一的时间边界
     * @param batchSize  单批行数
     * @param maxBatches 单轮批数上界；到界即停，剩余行由下一轮继续
     * @param operation  实际执行的一批操作
     * @return 本轮累计影响行数
     */
    public int run(String label, LocalDateTime now, int batchSize, int maxBatches,
                   BatchOperation operation) {
        int total = 0;
        try {
            for (int batch = 1; batch <= maxBatches; batch++) {
                long startedAtNanos = System.nanoTime();
                int affected = operation.run(now, batchSize);
                total += affected;
                long elapsedMs = elapsedMillis(startedAtNanos);

                if (affected < batchSize) {
                    break;  // 已排空：本批没吃满，说明后面没有更多到期行了
                }
                if (elapsedMs > properties.getCleanupSlowBatchMs()) {
                    // 退让而不是硬打：单批变慢是"数据库正在吃紧"的信号，
                    // 继续跑完剩下的批次只会加剧争用，而清理任务没有任何时效性。
                    log.warn("[Refresh] {} 第 {} 批耗时 {}ms，超过 {}ms 阈值，主动暂停本轮"
                                    + "（疑似数据库负载高）；剩余行由下一轮继续",
                            label, batch, elapsedMs, properties.getCleanupSlowBatchMs());
                    break;
                }
                if (batch == maxBatches) {
                    log.info("[Refresh] {} 达到单轮批数上界 {}（本轮 {} 行），剩余行由下一轮继续",
                            label, maxBatches, total);
                    break;
                }
                if (!sleepBetweenBatches()) {
                    log.info("[Refresh] {} 被中断，停止本轮（已处理 {} 行）", label, total);
                    break;
                }
            }
        } catch (RuntimeException ex) {
            // 单轮失败不能中断调度：定时任务停掉比慢一点危险得多。
            // 删除与清空都是幂等的，下一轮重试同一批是安全的。
            log.warn("[Refresh] {} 本轮失败，下一轮重试：{}", label, ex.getMessage());
        }
        return total;
    }

    /**
     * 批间停顿 + jitter（§42：100～500ms）。
     *
     * <p>jitter 的意义不是"更好看"：多实例（ShedLock 未启用时）或与业务高峰重叠时，
     * 固定间隔会让清理批次与轮换写入形成同频共振，锁等待被反复放大。</p>
     *
     * @return false 表示被中断，调用方应立即停止本轮
     */
    private boolean sleepBetweenBatches() {
        long base = Math.max(0L, properties.getHistoryCleanupSleepMs());
        long jitter = base == 0L ? 0L : ThreadLocalRandom.current().nextLong(base + 1L);
        try {
            Thread.sleep(base + jitter);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}
