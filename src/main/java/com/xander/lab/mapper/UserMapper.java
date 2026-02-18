package com.xander.lab.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
