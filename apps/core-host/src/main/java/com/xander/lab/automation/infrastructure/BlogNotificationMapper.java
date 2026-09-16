package com.xander.lab.automation.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.automation.domain.BlogNotification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BlogNotificationMapper extends BaseMapper<BlogNotification> {

    @Select("""
            SELECT COUNT(*)
            FROM blog_notification
            WHERE user_id = #{userId}
              AND is_read = 0
            """)
    long countUnread(@org.apache.ibatis.annotations.Param("userId") Long userId);
}