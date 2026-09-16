package com.xander.lab.identity.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.identity.domain.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 用户 Mapper
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 解绑微信 openid（账号合并时把 openid 从临时号挂到目标号前调用）。
     * 显式 SQL 而非 updateById(null 字段)：BaseMapper 默认跳过 null 字段，
     * 只传 openid=null 时不会生成 SET 子句导致 SQL 语法错误。
     */
    @Update("UPDATE sys_user SET openid = NULL WHERE id = #{id}")
    int clearOpenid(@Param("id") Long id);

    /** 在数据库时钟上累加订阅天数，避免重复客户端请求覆盖已购买周期。 */
    @Update("""
            UPDATE sys_user
               SET tier = CASE
                       WHEN FIELD(#{tier}, 'FREE', 'PLUS', 'PRO', 'ULTRA')
                            > FIELD(COALESCE(tier, 'FREE'), 'FREE', 'PLUS', 'PRO', 'ULTRA')
                       THEN #{tier} ELSE COALESCE(tier, 'FREE') END,
                   tier_expires_at = DATE_ADD(
                       CASE WHEN tier_expires_at IS NOT NULL AND tier_expires_at > NOW()
                            THEN tier_expires_at ELSE NOW() END,
                       INTERVAL #{durationDays} DAY),
                   updated_at = NOW()
             WHERE id = #{userId}
            """)
    int grantSubscription(@Param("userId") Long userId,
                          @Param("tier") String tier,
                          @Param("durationDays") int durationDays);
}
