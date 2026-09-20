package com.xander.lab.identity.application;

import com.xander.lab.identity.infrastructure.AuthRefreshTokenHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 刷新历史的保留策略事务边界。
 *
 * <h3>为什么单独一个类，而不是让清理任务直接调 Mapper</h3>
 * <p>必须「<b>每批独立事务</b>」。如果清理任务自己调 Mapper，那么事务边界就由
 * 调度方法上的注解决定 —— 而把 {@code @Transactional} 写在<b>循环外面</b>是这里最容易犯、
 * 后果最重的错：20 批 × 500 行会在<b>一个</b>事务里删 10,000 行，并且批间 sleep 期间
 * 一直持锁（4~8 秒），于是并发的轮换写入会撞上锁等待，undo log 与复制延迟一起膨胀。
 * 更糟的是它没有任何功能性症状 —— 测试全绿、功能正常，只在生产高峰暴露。</p>
 *
 * <p>把每批做成独立 bean 的 {@code @Transactional} 方法后，「一批一事务」成为
 * <b>结构性事实</b>，而不是一个可以被悄悄挪走的注解位置。两个清理任务共用本类，
 * 所以两条路径的事务语义不可能分叉。</p>
 *
 * <h3>这些任务不是安全边界</h3>
 * <p>密文的<b>逻辑</b>过期由时间判断（
 * {@code AuthRefreshTokenHistory#isCiphertextReadable}），业务代码在 grace 到期后
 * 拒绝返回，<b>不依赖</b>清理任务准点运行。所以本类清空的是"已经没用的字节"，
 * 不是"还在生效的凭据"：清理晚跑几十秒不会放开任何东西。</p>
 *
 * <p>同理，清理任务删除的行 {@code retained_until} 已过期 —— 它们既超出 Reuse 判定窗口，
 * 也超出幂等返回窗口，删除它们不会让任何仍可被识别的盗用变成不可识别。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthRefreshRetentionService {

    private final AuthRefreshTokenHistoryMapper historyMapper;

    /**
     * 删除一批已过保留期的历史行。
     *
     * @param now       本轮的时间边界，整轮共用同一个值（见调度器的说明）
     * @param batchSize 单批行数
     * @return 实际删除行数
     */
    @Transactional
    public int deleteExpiredHistory(LocalDateTime now, int batchSize) {
        return historyMapper.deleteExpired(now, batchSize);
    }

    /**
     * 清空一批已过期的 successor 密文。
     *
     * <p>只置空密文字段、不删行：行的 Reuse 判定窗口（{@code retained_until}，24 小时）
     * 通常远长于密文窗口（秒级）。删行会让这段时间里的盗用无法被识别。</p>
     *
     * @return 实际清空行数
     */
    @Transactional
    public int clearExpiredCiphertext(LocalDateTime now, int batchSize) {
        return historyMapper.clearExpiredCiphertext(now, batchSize);
    }
}
