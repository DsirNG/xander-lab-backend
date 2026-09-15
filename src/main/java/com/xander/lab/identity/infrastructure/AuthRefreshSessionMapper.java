package com.xander.lab.identity.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.identity.domain.AuthRefreshSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 刷新会话 Mapper（V9 §32 / §34）。
 *
 * <p>轮换必须在事务里 {@code SELECT ... FOR UPDATE} 锁住会话行，否则并发刷新
 * （同一用户多标签页）会各自生成一个 successor，把正常使用误判成 theft。
 * 服务端 grace 是并发安全的第一道保障，行锁是第二道。</p>
 */
@Mapper
public interface AuthRefreshSessionMapper extends BaseMapper<AuthRefreshSession> {

    /**
     * 加行锁读取会话，供轮换事务使用。
     *
     * <p>不能用 BaseMapper.selectById：它不带 {@code FOR UPDATE}，
     * 并发刷新会读到同一份旧状态。</p>
     */
    @Select("SELECT * FROM auth_refresh_session WHERE sid = #{sid} FOR UPDATE")
    AuthRefreshSession selectForUpdate(@Param("sid") String sid);

    /** 撤销单个会话（普通 logout）。 */
    @Update("""
            UPDATE auth_refresh_session
               SET status = 'REVOKED',
                   revoked_at = #{now},
                   revoked_reason = #{reason},
                   updated_at = #{now}
             WHERE sid = #{sid}
               AND status = 'ACTIVE'
            """)
    int revokeSession(@Param("sid") String sid,
                      @Param("reason") String reason,
                      @Param("now") LocalDateTime now);

    /**
     * 撤销整个会话族（Reuse Detection 命中）。
     *
     * <p>V9 §39 第 3 行：真正的 Reuse 必须 revoke 当前 family，
     * 而不只是当前会话 —— 攻击者与受害者可能各自持有族内不同代的 token。</p>
     */
    @Update("""
            UPDATE auth_refresh_session
               SET status = 'REVOKED',
                   revoked_at = #{now},
                   revoked_reason = #{reason},
                   updated_at = #{now}
             WHERE family_id = #{familyId}
               AND status = 'ACTIVE'
            """)
    int revokeFamily(@Param("familyId") String familyId,
                     @Param("reason") String reason,
                     @Param("now") LocalDateTime now);

    /**
     * 撤销某账号的全部会话。
     *
     * <p>管理员封禁（V9 §26 第 2 条）与「登出全部设备」都走这里。
     * 与旧的 {@code revokeSessions} 相比，这里是一条按索引更新的语句，
     * 不需要遍历无 TTL 的 Redis Set —— 旧实现的封禁延迟随时间单调恶化（V9 §56）。</p>
     */
    @Update("""
            UPDATE auth_refresh_session
               SET status = 'REVOKED',
                   revoked_at = #{now},
                   revoked_reason = #{reason},
                   updated_at = #{now}
             WHERE account_id = #{accountId}
               AND status = 'ACTIVE'
            """)
    int revokeByAccount(@Param("accountId") Long accountId,
                        @Param("reason") String reason,
                        @Param("now") LocalDateTime now);

    /** 列出某账号仍有效的会话，供设备 / 会话管理 UI 使用（V9 §25）。 */
    @Select("""
            SELECT * FROM auth_refresh_session
             WHERE account_id = #{accountId}
               AND status = 'ACTIVE'
             ORDER BY issued_at DESC
            """)
    List<AuthRefreshSession> listActiveByAccount(@Param("accountId") Long accountId);
}
