package com.xander.lab.identity.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.identity.domain.SecurityEventOutboxEntry;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 安全事件发件箱 Mapper。
 */
@Mapper
public interface SecurityEventOutboxMapper extends BaseMapper<SecurityEventOutboxEntry> {

    /**
     * 写入一条可立即投递的发件箱事件。
     *
     * <p>{@code next_attempt_at} 必须和 {@link #claimDue} 的 {@code NOW(3)} 使用同一
     * 时钟。不要调用 MyBatis-Plus 的通用 {@code insert} 把 JVM 时间写进去：容器时钟
     * 与数据库时钟有偏差时，首投会提前或延迟；失败重试路径已经遵守这个约束。</p>
     */
    @Insert("""
            INSERT INTO security_event_outbox
                (aggregate_type, aggregate_id, event_type, payload, status, attempts, next_attempt_at)
            VALUES
                (#{entry.aggregateType}, #{entry.aggregateId}, #{entry.eventType}, #{entry.payload},
                 #{entry.status}, #{entry.attempts}, NOW(3))
            """)
    int insertPending(@Param("entry") SecurityEventOutboxEntry entry);

    /**
     * 领取待投递事件。
     *
     * <p>Publisher Worker 是 {@code global_once}，用 MySQL-backed ShedLock 互斥；
     * 即便锁失效导致重复投递，消费端也必须是幂等的（本地 Set 插入天然幂等）。</p>
     *
     * <p>刻意<b>不</b>领取 {@code DEAD}：那是"已判定不再重试"的终态。</p>
     */
    @Select("""
            SELECT * FROM security_event_outbox
             WHERE status IN ('PENDING', 'FAILED')
               AND next_attempt_at <= NOW(3)
             ORDER BY id
             LIMIT #{limit}
            """)
    List<SecurityEventOutboxEntry> claimDue(@Param("limit") int limit);

    /**
     * 标记投递成功。
     *
     * <p>{@code published_at} 取数据库时钟而不是 JVM 时钟：
     * {@code admin_ban_propagation_latency} 由 {@code published_at - created_at} 得出，
     * 两端必须同源，否则容器与数据库的时区/时钟偏差会直接污染这个 SLO 指标。</p>
     *
     * <p>{@code attempts} 用 SQL 侧自增而不是"读出来 +1 再写回"，避免读改写竞态。</p>
     */
    @Update("""
            UPDATE security_event_outbox
               SET status = 'PUBLISHED',
                   attempts = attempts + 1,
                   published_at = NOW(3),
                   last_error = NULL
             WHERE id = #{id}
            """)
    int markPublished(@Param("id") Long id);

    /**
     * 记录一次投递失败。
     *
     * <p>{@code next_attempt_at} 同样用数据库时钟做退避
     * （{@code NOW(3) + INTERVAL ? MICROSECOND}），理由与 {@link #markPublished} 相同：
     * 领取条件 {@code next_attempt_at <= NOW(3)} 用的是数据库时钟，写入端也必须用它，
     * 否则"退避 1 秒"在时区不一致的部署里会变成"立即重试"或"退避几小时"。</p>
     *
     * @param status     {@code FAILED}（可重试）或 {@code DEAD}（终态）
     * @param lastError  失败原因，调用方须按列宽截断
     * @param backoffMs  退避毫秒数；{@code DEAD} 时无意义，传 0
     */
    @Update("""
            UPDATE security_event_outbox
               SET status = #{status},
                   attempts = attempts + 1,
                   last_error = #{lastError},
                   next_attempt_at = NOW(3) + INTERVAL #{backoffMs} MICROSECOND
             WHERE id = #{id}
            """)
    int markAttemptFailed(@Param("id") Long id,
                          @Param("status") String status,
                          @Param("lastError") String lastError,
                          @Param("backoffMs") long backoffMs);
}
