package com.xander.lab.identity.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.identity.domain.AuthRefreshTokenHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 刷新令牌轮换历史 Mapper。
 */
@Mapper
public interface AuthRefreshTokenHistoryMapper extends BaseMapper<AuthRefreshTokenHistory> {

    /**
     * 每 sid 条数上界。
     *
     * <p>{@code retention} 是时间上界，{@code 48 代} 是条数上界，两者都必须生效。
     * 条数上界的必要性：极端高频轮换（异常客户端循环刷新）下，24 小时窗口仍可能
     * 堆积大量行。</p>
     *
     * <p>实现方式：轮换成功写 history 后，保留 generation 最大的 N 行，
     * 其余行直接把 {@code retained_until} 拉到现在，交给清理任务删除。</p>
     *
     * <p>MySQL 不允许在 UPDATE 的子查询里直接引用被更新的表（错误 1093），
     * 因此把子查询包一层派生表。</p>
     */
    @Update("""
            UPDATE auth_refresh_token_history
               SET retained_until = #{now}
             WHERE sid = #{sid}
               AND retained_until > #{now}
               AND generation <= (
                     SELECT max_generation FROM (
                       SELECT MAX(generation) AS max_generation
                         FROM auth_refresh_token_history
                        WHERE sid = #{sid}
                     ) AS snapshot
                   ) - #{maxGenerations}
            """)
    int pruneGenerationsBeyondBound(@Param("sid") String sid,
                                    @Param("maxGenerations") int maxGenerations,
                                    @Param("now") LocalDateTime now);

    /** 标记某行已被识别为真实 Reuse。 */
    @Update("""
            UPDATE auth_refresh_token_history
               SET reuse_detected_at = #{now}
             WHERE token_id = #{tokenId}
               AND reuse_detected_at IS NULL
            """)
    int markReuseDetected(@Param("tokenId") String tokenId, @Param("now") LocalDateTime now);

    /**
     * 历史清理。
     *
     * <p>批量删除、每批独立事务、批间 sleep，依赖
     * {@code idx_refresh_history_retained_until}。不得改成大批量集中 DELETE。</p>
     */
    @Update("""
            DELETE FROM auth_refresh_token_history
             WHERE retained_until < #{now}
             ORDER BY retained_until
             LIMIT #{limit}
            """)
    int deleteExpired(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 密文物理清理。
     *
     * <p>只清空密文字段，不删行 —— 行的 Reuse 判定窗口（retained_until）通常
     * 远长于密文窗口。依赖 {@code idx_refresh_history_ciphertext_expires}。</p>
     */
    @Update("""
            UPDATE auth_refresh_token_history
               SET successor_token_ciphertext = NULL
             WHERE successor_ciphertext_expires_at < #{now}
               AND successor_token_ciphertext IS NOT NULL
             ORDER BY successor_ciphertext_expires_at
             LIMIT #{limit}
            """)
    int clearExpiredCiphertext(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /** 统计某会话的历史行数，供指标与单测使用。 */
    @Select("SELECT COUNT(*) FROM auth_refresh_token_history WHERE sid = #{sid}")
    int countBySid(@Param("sid") String sid);

    /** 全局历史行数，供 `refresh_history_rows` 指标使用。 */
    @Select("SELECT COUNT(*) FROM auth_refresh_token_history")
    int countAll();

    /** 全局会话行数，供 `refresh_session_rows` 指标使用。 */
    @Select("SELECT COUNT(*) FROM auth_refresh_session")
    int countSessions();
}
