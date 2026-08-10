package com.xander.lab.automation.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.automation.domain.BlogPlanRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BlogPlanRunMapper extends BaseMapper<BlogPlanRun> {

    @Select("""
            SELECT CASE
                WHEN EXISTS (
                    SELECT 1
                    FROM blog_plan_run
                    WHERE plan_id = #{planId}
                      AND scheduled_at = #{scheduledAt}
                ) THEN 1
                ELSE 0
            END
            """)
    boolean existsForPlanAndScheduledAt(@org.apache.ibatis.annotations.Param("planId") Long planId,
                                        @org.apache.ibatis.annotations.Param("scheduledAt") java.time.Instant scheduledAt);
}