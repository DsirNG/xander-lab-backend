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
    @Update("UPDATE sys_user SET openid = NULL, updated_at = NOW() WHERE id = #{id}")
    int clearOpenid(@Param("id") Long id);
}
