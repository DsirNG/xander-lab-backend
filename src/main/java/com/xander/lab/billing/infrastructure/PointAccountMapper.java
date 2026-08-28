package com.xander.lab.billing.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.billing.domain.PointAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 积分账户 Mapper。
 */
@Mapper
public interface PointAccountMapper extends BaseMapper<PointAccount> {

    /** 锁定并读取账户，保证余额快照与本次流水、账户变更一致。 */
    @Select("SELECT user_id, balance, created_at, updated_at FROM sys_user_points WHERE user_id = #{userId} FOR UPDATE")
    PointAccount selectForUpdate(@Param("userId") Long userId);

    /** 原子扣减余额，返回受影响行数；没有余额下限，允许负余额。 */
    @Update("UPDATE sys_user_points SET balance = balance - #{points} WHERE user_id = #{userId}")
    int deduct(@Param("userId") Long userId, @Param("points") Long points);

    /** 原子增加余额，返回受影响行数。 */
    @Update("UPDATE sys_user_points SET balance = balance + #{points} WHERE user_id = #{userId}")
    int add(@Param("userId") Long userId, @Param("points") Long points);
}
