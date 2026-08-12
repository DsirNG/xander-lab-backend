package com.xander.lab.billing.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.billing.domain.PointAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 积分账户 Mapper。
 */
@Mapper
public interface PointAccountMapper extends BaseMapper<PointAccount> {

    /** 原子扣减余额，返回受影响行数；没有余额下限，允许负余额。 */
    @Update("UPDATE sys_user_points SET balance = balance - #{points}, updated_at = NOW() WHERE user_id = #{userId}")
    int deduct(@Param("userId") Long userId, @Param("points") Long points);

    /** 原子增加余额，返回受影响行数。 */
    @Update("UPDATE sys_user_points SET balance = balance + #{points}, updated_at = NOW() WHERE user_id = #{userId}")
    int add(@Param("userId") Long userId, @Param("points") Long points);
}